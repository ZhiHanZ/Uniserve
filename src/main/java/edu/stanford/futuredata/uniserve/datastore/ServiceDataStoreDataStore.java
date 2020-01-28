package edu.stanford.futuredata.uniserve.datastore;

import edu.stanford.futuredata.uniserve.*;
import edu.stanford.futuredata.uniserve.interfaces.Row;
import edu.stanford.futuredata.uniserve.interfaces.Shard;
import edu.stanford.futuredata.uniserve.interfaces.WriteQueryPlan;
import edu.stanford.futuredata.uniserve.utilities.DataStoreDescription;
import edu.stanford.futuredata.uniserve.utilities.Utilities;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

class ServiceDataStoreDataStore<R extends Row, S extends Shard> extends DataStoreDataStoreGrpc.DataStoreDataStoreImplBase {

    private static final Logger logger = LoggerFactory.getLogger(ServiceBrokerDataStore.class);
    private final DataStore<R, S> dataStore;

    ServiceDataStoreDataStore(DataStore<R, S> dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public void bootstrapReplica(BootstrapReplicaMessage request, StreamObserver<BootstrapReplicaResponse> responseObserver) {
        responseObserver.onNext(bootstrapReplicaHandler(request));
        responseObserver.onCompleted();
    }

    private BootstrapReplicaResponse bootstrapReplicaHandler(BootstrapReplicaMessage request) {
        int shardNum = request.getShard();
        dataStore.shardLockMap.get(shardNum).readLock().lock();
        Integer replicaVersion = request.getVersionNumber();
        Integer primaryVersion = dataStore.shardVersionMap.get(shardNum);
        assert(primaryVersion != null);
        assert(replicaVersion <= primaryVersion);
        Map<Integer, Pair<WriteQueryPlan<R, S>, List<R>>> shardWriteLog = dataStore.writeLog.get(shardNum);
        if (replicaVersion.equals(primaryVersion)) {
            DataStoreDescription dsDescription = dataStore.zkCurator.getDSDescription(request.getDsID());
            ManagedChannel channel = ManagedChannelBuilder.forAddress(dsDescription.host, dsDescription.port).usePlaintext().build();
            DataStoreDataStoreGrpc.DataStoreDataStoreStub asyncStub = DataStoreDataStoreGrpc.newStub(channel);
            dataStore.replicaChannelsMap.get(shardNum).add(channel);
            dataStore.replicaStubsMap.get(shardNum).add(asyncStub);
        }
        dataStore.shardLockMap.get(shardNum).readLock().unlock();
        List<WriteQueryPlan<R, S>> writeQueryPlans = new ArrayList<>();
        List<R[]> rowListList = new ArrayList<>();
        for (int v = replicaVersion + 1; v <= primaryVersion; v++) {
            writeQueryPlans.add(shardWriteLog.get(v).getValue0());
            rowListList.add((R[]) shardWriteLog.get(v).getValue1().toArray(new Row[0]));
        }
        WriteQueryPlan<R, S>[] writeQueryPlansArray = writeQueryPlans.toArray(new WriteQueryPlan[0]);
        R[][] rowArrayArray = rowListList.toArray((R[][]) new Row[0][]);
        return BootstrapReplicaResponse.newBuilder().setReturnCode(0)
                .setVersionNumber(primaryVersion)
                .setWriteData(Utilities.objectToByteString(rowArrayArray))
                .setWriteQueries(Utilities.objectToByteString(writeQueryPlansArray))
                .build();
    }

    private Map<Integer, CommitLockerThread<R, S>> activeCLTs = new HashMap<>();

    @Override
    public StreamObserver<ReplicaPreCommitMessage> replicaPreCommit(StreamObserver<ReplicaPreCommitResponse> responseObserver) {
        return new StreamObserver<>() {
            int shardNum;
            long txID;
            int versionNumber;
            WriteQueryPlan<R, S> writeQueryPlan;
            List<R[]> rowArrayList = new ArrayList<>();

            @Override
            public void onNext(ReplicaPreCommitMessage replicaPreCommitMessage) {
                versionNumber = replicaPreCommitMessage.getVersionNumber();
                shardNum = replicaPreCommitMessage.getShard();
                txID = replicaPreCommitMessage.getTxID();
                writeQueryPlan = (WriteQueryPlan<R, S>) Utilities.byteStringToObject(replicaPreCommitMessage.getSerializedQuery()); // TODO:  Only send this once.
                R[] rowChunk = (R[]) Utilities.byteStringToObject(replicaPreCommitMessage.getRowData());
                rowArrayList.add(rowChunk);
            }

            @Override
            public void onError(Throwable throwable) {
                assert (false);
            }

            @Override
            public void onCompleted() {
                List<R> rowList = rowArrayList.stream().flatMap(Arrays::stream).collect(Collectors.toList());
                responseObserver.onNext(replicaPreCommitHandler(shardNum, txID, writeQueryPlan, rowList, versionNumber));
                responseObserver.onCompleted();
            }
        };
    }

    private ReplicaPreCommitResponse replicaPreCommitHandler(int shardNum, long txID, WriteQueryPlan<R, S> writeQueryPlan, List<R> rows, int versionNumber) {
        if (dataStore.replicaShardMap.containsKey(shardNum)) {
            S shard = dataStore.replicaShardMap.get(shardNum);
            // Use the CommitLockerThread to acquire the shard's write lock.
            CommitLockerThread<R, S> commitLockerThread =
                    new CommitLockerThread<>(activeCLTs, shardNum, writeQueryPlan, rows, dataStore.shardLockMap.get(shardNum).writeLock(), txID, dataStore.dsID);
            commitLockerThread.acquireLock();
            assert(versionNumber == dataStore.shardVersionMap.get(shardNum));
            boolean replicaWriteSuccess = writeQueryPlan.preCommit(shard, rows);
            int returnCode;
            if (replicaWriteSuccess) {
                returnCode = 0;
            } else {
                returnCode = 1;
            }
            return ReplicaPreCommitResponse.newBuilder().setReturnCode(returnCode).build();
        } else {
            logger.warn("DS{} replica got write request for absent shard {}", dataStore.dsID, shardNum);
            return ReplicaPreCommitResponse.newBuilder().setReturnCode(1).build();
        }
    }

    @Override
    public void replicaCommit(ReplicaCommitMessage request, StreamObserver<ReplicaCommitResponse> responseObserver) {
        responseObserver.onNext(replicaCommitHandler(request));
        responseObserver.onCompleted();
    }

    private ReplicaCommitResponse replicaCommitHandler(ReplicaCommitMessage request) {
        int shardNum = request.getShard();
        CommitLockerThread<R, S> commitCLT = activeCLTs.get(shardNum);
        assert(commitCLT != null);  // The commit locker thread holds the shard's write lock.
        assert(commitCLT.txID == request.getTxID());
        WriteQueryPlan<R, S> writeQueryPlan = commitCLT.writeQueryPlan;
        if (dataStore.replicaShardMap.containsKey(shardNum)) {
            boolean commitOrAbort = request.getCommitOrAbort(); // Commit on true, abort on false.
            S shard = dataStore.replicaShardMap.get(shardNum);
            if (commitOrAbort) {
                writeQueryPlan.commit(shard);
                int newVersionNumber = dataStore.shardVersionMap.get(shardNum) + 1;
                Map<Integer, Pair<WriteQueryPlan<R, S>, List<R>>> shardWriteLog = dataStore.writeLog.get(shardNum);
                shardWriteLog.put(newVersionNumber, new Pair<>(writeQueryPlan, commitCLT.rows));
                dataStore.shardVersionMap.put(shardNum, newVersionNumber);  // Increment version number
            } else {
                writeQueryPlan.abort(shard);
            }
            // Have the commit locker thread release the shard's write lock.
            commitCLT.releaseLock();
        } else {
            logger.error("DS{} Got valid commit request on absent shard {} (!!!!!)", dataStore.dsID, shardNum);
            assert(false);
        }
        return ReplicaCommitResponse.newBuilder().build();
    }

    @Override
    public void dataStorePing(DataStorePingMessage request, StreamObserver<DataStorePingResponse> responseObserver) {
        responseObserver.onNext(DataStorePingResponse.newBuilder().build());
        responseObserver.onCompleted();
    }
}
