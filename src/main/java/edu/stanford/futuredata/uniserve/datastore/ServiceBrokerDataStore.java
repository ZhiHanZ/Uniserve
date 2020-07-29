package edu.stanford.futuredata.uniserve.datastore;

import com.google.protobuf.ByteString;
import edu.stanford.futuredata.uniserve.*;
import edu.stanford.futuredata.uniserve.broker.Broker;
import edu.stanford.futuredata.uniserve.interfaces.AnchoredReadQueryPlan;
import edu.stanford.futuredata.uniserve.interfaces.Row;
import edu.stanford.futuredata.uniserve.interfaces.Shard;
import edu.stanford.futuredata.uniserve.interfaces.WriteQueryPlan;
import edu.stanford.futuredata.uniserve.utilities.ConsistentHash;
import edu.stanford.futuredata.uniserve.utilities.Utilities;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

class ServiceBrokerDataStore<R extends Row, S extends Shard> extends BrokerDataStoreGrpc.BrokerDataStoreImplBase {

    private static final Logger logger = LoggerFactory.getLogger(ServiceBrokerDataStore.class);
    private final DataStore<R, S> dataStore;

    ServiceBrokerDataStore(DataStore<R, S> dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public StreamObserver<WriteQueryMessage> writeQuery(StreamObserver<WriteQueryResponse> responseObserver) {
        return new StreamObserver<>() {
            int shardNum;
            long txID;
            WriteQueryPlan<R, S> writeQueryPlan;
            List<R[]> rowArrayList = new ArrayList<>();
            int lastState = DataStore.COLLECT;
            List<R> rows;
            List<StreamObserver<ReplicaWriteMessage>> replicaObservers = new ArrayList<>();
            Semaphore commitSemaphore = new Semaphore(0);
            WriteLockerThread t;

            @Override
            public void onNext(WriteQueryMessage writeQueryMessage) {
                int writeState = writeQueryMessage.getWriteState();
                if (writeState == DataStore.COLLECT) {
                    assert (lastState == DataStore.COLLECT);
                    shardNum = writeQueryMessage.getShard();
                    dataStore.createShardMetadata(shardNum);
                    txID = writeQueryMessage.getTxID();
                    writeQueryPlan = (WriteQueryPlan<R, S>) Utilities.byteStringToObject(writeQueryMessage.getSerializedQuery()); // TODO:  Only send this once.
                    R[] rowChunk = (R[]) Utilities.byteStringToObject(writeQueryMessage.getRowData());
                    rowArrayList.add(rowChunk);
                } else if (writeState == DataStore.PREPARE) {
                    assert (lastState == DataStore.COLLECT);
                    rows = rowArrayList.stream().flatMap(Arrays::stream).collect(Collectors.toList());
                    if (dataStore.shardLockMap.containsKey(shardNum)) {
                        t = new WriteLockerThread(dataStore.shardLockMap.get(shardNum));
                        t.acquireLock();
                        responseObserver.onNext(prepareWriteQuery(shardNum, txID, writeQueryPlan, false));
                    } else {
                        responseObserver.onNext(WriteQueryResponse.newBuilder().setReturnCode(Broker.QUERY_RETRY).build());
                    }
                } else if (writeState == DataStore.COMMIT) {
                    assert (lastState == DataStore.PREPARE);
                    commitWriteQuery(shardNum, txID, writeQueryPlan);
                    lastState = writeState;
                    t.releaseLock();
                } else if (writeState == DataStore.ABORT) {
                    assert (lastState == DataStore.PREPARE);
                    abortWriteQuery(shardNum, txID, writeQueryPlan, false);
                    lastState = writeState;
                    t.releaseLock();
                }
                lastState = writeState;
            }

            @Override
            public void onError(Throwable throwable) {
                logger.warn("DS{} Primary Write RPC Error Shard {} {}", dataStore.dsID, shardNum, throwable.getMessage());
                if (lastState == DataStore.PREPARE) {
                    if (dataStore.zkCurator.getTransactionStatus(txID) == DataStore.COMMIT) {
                        commitWriteQuery(shardNum, txID, writeQueryPlan);
                    } else {
                        abortWriteQuery(shardNum, txID, writeQueryPlan, false);
                    }
                    t.releaseLock();
                }
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }

            private WriteQueryResponse prepareWriteQuery(int shardNum, long txID, WriteQueryPlan<R, S> writeQueryPlan, boolean preempt) {
                if (dataStore.consistentHash.getBucket(shardNum) == dataStore.dsID) {
                    dataStore.ensureShardCached(shardNum);
                    S shard = dataStore.primaryShardMap.get(shardNum);
                    assert(shard != null);
                    List<DataStoreDataStoreGrpc.DataStoreDataStoreStub> replicaStubs =
                            preempt ? Collections.emptyList() // Do not touch replicas if resuming from a preemption.
                            : dataStore.replicaDescriptionsMap.get(shardNum).stream().map(i -> i.stub).collect(Collectors.toList());
                    int numReplicas = replicaStubs.size();
                    R[] rowArray;
                    rowArray = (R[]) rows.toArray(new Row[0]);
                    AtomicBoolean success = new AtomicBoolean(true);
                    Semaphore prepareSemaphore = new Semaphore(0);
                    for (DataStoreDataStoreGrpc.DataStoreDataStoreStub stub : replicaStubs) {
                        StreamObserver<ReplicaWriteMessage> observer = stub.replicaWrite(new StreamObserver<>() {
                            @Override
                            public void onNext(ReplicaWriteResponse replicaResponse) {
                                if (replicaResponse.getReturnCode() != 0) {
                                    logger.warn("DS{} Replica Prepare Failed Shard {}", dataStore.dsID, shardNum);
                                    success.set(false);
                                }
                                prepareSemaphore.release();
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                logger.warn("DS{} Replica Prepare RPC Failed Shard {} {}", dataStore.dsID, shardNum, throwable.getMessage());
                                success.set(false);
                                prepareSemaphore.release();
                                commitSemaphore.release();
                            }

                            @Override
                            public void onCompleted() {
                                commitSemaphore.release();
                            }
                        });
                        final int stepSize = 10000;
                        for (int i = 0; i < rowArray.length; i += stepSize) {
                            ByteString serializedQuery = Utilities.objectToByteString(writeQueryPlan);
                            R[] rowSlice = Arrays.copyOfRange(rowArray, i, Math.min(rowArray.length, i + stepSize));
                            ByteString rowData = Utilities.objectToByteString(rowSlice);
                            ReplicaWriteMessage rm = ReplicaWriteMessage.newBuilder()
                                    .setShard(shardNum)
                                    .setSerializedQuery(serializedQuery)
                                    .setRowData(rowData)
                                    .setVersionNumber(dataStore.shardVersionMap.get(shardNum))
                                    .setWriteState(DataStore.COLLECT)
                                    .setTxID(txID)
                                    .build();
                            observer.onNext(rm);
                        }
                        ReplicaWriteMessage rm = ReplicaWriteMessage.newBuilder()
                                .setWriteState(DataStore.PREPARE)
                                .build();
                        observer.onNext(rm);
                        replicaObservers.add(observer);
                    }
                    boolean primaryWriteSuccess = writeQueryPlan.preCommit(shard, rows);
                    if (!preempt) {
                        lastState = DataStore.PREPARE;
                    }
                    try {
                        prepareSemaphore.acquire(numReplicas);
                    } catch (InterruptedException e) {
                        logger.error("DS{} Write Query Interrupted Shard {}: {}", dataStore.dsID, shardNum, e.getMessage());
                        assert (false);
                    }
                    int returnCode;
                    if (primaryWriteSuccess && success.get()) {
                        returnCode = Broker.QUERY_SUCCESS;
                    } else {
                        returnCode = Broker.QUERY_FAILURE;
                    }
                    return WriteQueryResponse.newBuilder().setReturnCode(returnCode).build();
                } else {
                    logger.warn("DS{} Primary got write request for unassigned shard {}", dataStore.dsID, shardNum);
                    t.releaseLock();
                    return WriteQueryResponse.newBuilder().setReturnCode(Broker.QUERY_RETRY).build();
                }
            }

            private void commitWriteQuery(int shardNum, long txID, WriteQueryPlan<R, S> writeQueryPlan) {
                S shard = dataStore.primaryShardMap.get(shardNum);
                for (StreamObserver<ReplicaWriteMessage> observer : replicaObservers) {
                    ReplicaWriteMessage rm = ReplicaWriteMessage.newBuilder()
                            .setWriteState(DataStore.COMMIT)
                            .build();
                    observer.onNext(rm);
                    observer.onCompleted();
                }
                writeQueryPlan.commit(shard);
                int newVersionNumber = dataStore.shardVersionMap.get(shardNum) + 1;
                Map<Integer, Pair<WriteQueryPlan<R, S>, List<R>>> shardWriteLog = dataStore.writeLog.get(shardNum);
                shardWriteLog.put(newVersionNumber, new Pair<>(writeQueryPlan, rows));
                dataStore.shardVersionMap.put(shardNum, newVersionNumber);  // Increment version number
                // Update materialized views.
                long firstWrittenTimestamp = rows.stream().mapToLong(Row::getTimeStamp).min().getAsLong();
                long lastWrittenTimestamp = rows.stream().mapToLong(Row::getTimeStamp).max().getAsLong();
                long lastExistingTimestamp =
                        dataStore.shardTimestampMap.compute(shardNum, (k, v) -> v == null ? lastWrittenTimestamp : Long.max(v, lastWrittenTimestamp));
                for (MaterializedView m: dataStore.materializedViewMap.get(shardNum).values()) {
                    m.updateView(dataStore.primaryShardMap.get(shardNum), firstWrittenTimestamp, lastExistingTimestamp);
                }
                // Upload the updated shard.
                if (dataStore.dsCloud != null) {
                    dataStore.uploadShardToCloud(shardNum);
                }
                try {
                    commitSemaphore.acquire(replicaObservers.size());
                } catch (InterruptedException e) {
                    logger.error("DS{} Write Query Interrupted Shard {}: {}", dataStore.dsID, shardNum, e.getMessage());
                    assert (false);
                }
            }

            private void abortWriteQuery(int shardNum, long txID, WriteQueryPlan<R, S> writeQueryPlan, boolean preempt) {
                S shard = dataStore.primaryShardMap.get(shardNum);
                if (preempt) {
                    writeQueryPlan.abort(shard);
                    return;
                }
                for (StreamObserver<ReplicaWriteMessage> observer : replicaObservers) {
                    ReplicaWriteMessage rm = ReplicaWriteMessage.newBuilder()
                            .setWriteState(DataStore.ABORT)
                            .build();
                    observer.onNext(rm);
                    observer.onCompleted();
                }
                writeQueryPlan.abort(shard);
                try {
                    commitSemaphore.acquire(replicaObservers.size());
                } catch (InterruptedException e) {
                    logger.error("DS{} Write Query Interrupted Shard {}: {}", dataStore.dsID, shardNum, e.getMessage());
                    assert (false);
                }
            }

        };
    }
    
    @Override
    public void anchoredReadQuery(AnchoredReadQueryMessage request,  StreamObserver<AnchoredReadQueryResponse> responseObserver) {
        responseObserver.onNext(anchoredReadQueryHandler(request));
        responseObserver.onCompleted();
    }


    private AnchoredReadQueryResponse anchoredReadQueryHandler(AnchoredReadQueryMessage m) {
        AnchoredReadQueryPlan<S, Object> plan =
                (AnchoredReadQueryPlan<S, Object>) Utilities.byteStringToObject(m.getSerializedQuery());
        Map<String, List<Integer>> allTargetShards = (Map<String, List<Integer>>) Utilities.byteStringToObject(m.getTargetShards());
        Map<String, List<ByteString>> ephemeralData = new HashMap<>();
        Map<String, S> ephemeralShards = new HashMap<>();
        int localShardNum = m.getTargetShard();
        String anchorTableName = plan.getAnchorTable();
        dataStore.createShardMetadata(localShardNum);
        dataStore.shardLockMap.get(localShardNum).readerLockLock();
        dataStore.ensureShardCached(localShardNum);
        S localShard = dataStore.primaryShardMap.get(localShardNum);
        assert(localShard != null);
        List<Integer> partitionKeys = plan.getPartitionKeys(localShard);
        for (String tableName: plan.getQueriedTables()) {
            S ephemeralShard = dataStore.createNewShard(dataStore.ephemeralShardNum.decrementAndGet()).get();
            ephemeralShards.put(tableName, ephemeralShard);
            if (!tableName.equals(anchorTableName)) {
                List<Integer> targetShards = allTargetShards.get(tableName);
                List<ByteString> tableEphemeralData = new CopyOnWriteArrayList<>();
                CountDownLatch latch = new CountDownLatch(targetShards.size());
                for (int targetShard : targetShards) {
                    int targetDSID = dataStore.consistentHash.getBucket(targetShard); // TODO:  If it's already here, use it.
                    ManagedChannel channel = dataStore.getChannelForDSID(targetDSID);
                    DataStoreDataStoreGrpc.DataStoreDataStoreStub stub = DataStoreDataStoreGrpc.newStub(channel);
                    AnchoredShuffleMessage g = AnchoredShuffleMessage.newBuilder()
                            .setShardNum(targetShard).setNumReducers(m.getNumReducers()).setReducerShardNum(localShardNum)
                            .setSerializedQuery(m.getSerializedQuery())
                            .setTxID(m.getTxID()).addAllPartitionKeys(partitionKeys).build();
                    StreamObserver<AnchoredShuffleResponse> responseObserver = new StreamObserver<>() {
                        @Override
                        public void onNext(AnchoredShuffleResponse r) {
                            if (r.getReturnCode() == Broker.QUERY_RETRY) {
                                onError(new Throwable());
                            } else {
                                tableEphemeralData.add(r.getShuffleData());
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            logger.info("DS{}  Shuffle data error shard {}", dataStore.dsID, targetShard);
                            int targetDSID = dataStore.consistentHash.getBucket(targetShard); // TODO:  If it's already here, use it.
                            ManagedChannel channel = dataStore.getChannelForDSID(targetDSID);
                            DataStoreDataStoreGrpc.DataStoreDataStoreStub stub = DataStoreDataStoreGrpc.newStub(channel);
                            stub.anchoredShuffle(g, this);
                        }

                        @Override
                        public void onCompleted() {
                            latch.countDown();
                        }
                    };
                    stub.anchoredShuffle(g, responseObserver);
                }
                try {
                    latch.await();
                } catch (InterruptedException ignored) {
                }
                ephemeralData.put(tableName, tableEphemeralData);
            }
        }
        ByteString b = plan.reducer(localShard, ephemeralData, ephemeralShards);
        dataStore.shardLockMap.get(localShardNum).readerLockUnlock();
        ephemeralShards.values().forEach(S::destroy);
        long unixTime = Instant.now().getEpochSecond();
        dataStore.QPSMap.get(localShardNum).merge(unixTime, 1, Integer::sum);
        return AnchoredReadQueryResponse.newBuilder().setReturnCode(Broker.QUERY_SUCCESS).setResponse(b).build();
    }

    @Override
    public void registerMaterializedView(RegisterMaterializedViewMessage request, StreamObserver<RegisterMaterializedViewResponse> responseObserver) {
        responseObserver.onNext(registerMaterializedViewHandler(request));
        responseObserver.onCompleted();
    }

    private RegisterMaterializedViewResponse registerMaterializedViewHandler(RegisterMaterializedViewMessage m) {
        int shardNum = m.getShard();
        String name = m.getName();
        AnchoredReadQueryPlan<S, Object> r = (AnchoredReadQueryPlan<S, Object>) Utilities.byteStringToObject(m.getSerializedQuery());
        dataStore.createShardMetadata(shardNum);
        dataStore.shardLockMap.get(shardNum).writerLockLock();
        if (dataStore.consistentHash.getBucket(shardNum) == dataStore.dsID) {
            dataStore.ensureShardCached(shardNum);
            S shard = dataStore.primaryShardMap.get(shardNum);
            if (dataStore.materializedViewMap.get(shardNum).containsKey(name)) {
                logger.warn("DS{} Shard {} reused MV name {}", dataStore.dsID, shardNum, name);
                dataStore.shardLockMap.get(shardNum).writerLockUnlock();
                return RegisterMaterializedViewResponse.newBuilder().setReturnCode(Broker.QUERY_FAILURE).build();
            }
            Long timestamp = dataStore.shardTimestampMap.getOrDefault(shardNum, Long.MIN_VALUE);
            ByteString intermediate = r.queryShard(Collections.singletonList(shard));
            MaterializedView v = new MaterializedView(r, timestamp, intermediate);
            dataStore.materializedViewMap.get(shardNum).put(name, v);
            int newVersionNumber = dataStore.shardVersionMap.get(shardNum) + 1;
            dataStore.shardVersionMap.put(shardNum, newVersionNumber);  // Increment version number
            // Upload the shard updated with the new MV.
            if (dataStore.dsCloud != null) {
                dataStore.uploadShardToCloud(shardNum);
            }
            dataStore.shardLockMap.get(shardNum).writerLockUnlock();
            List<DataStoreDataStoreGrpc.DataStoreDataStoreBlockingStub> replicaStubs =
                    dataStore.replicaDescriptionsMap.get(shardNum).stream().map(i -> DataStoreDataStoreGrpc.newBlockingStub(i.channel)).collect(Collectors.toList());
            for (DataStoreDataStoreGrpc.DataStoreDataStoreBlockingStub stub : replicaStubs) {
                ReplicaRegisterMVResponse response =
                        stub.replicaRegisterMV(ReplicaRegisterMVMessage.newBuilder().setShard(shardNum).setName(name).
                        setSerializedQuery(m.getSerializedQuery()).build());
                assert (response.getReturnCode() == Broker.QUERY_SUCCESS);
            }
            return RegisterMaterializedViewResponse.newBuilder().setReturnCode(Broker.QUERY_SUCCESS).build();
        } else {
            logger.warn("DS{} Got MV request for unassigned shard {}", dataStore.dsID, shardNum);
            dataStore.shardLockMap.get(shardNum).writerLockUnlock();
            return RegisterMaterializedViewResponse.newBuilder().setReturnCode(Broker.QUERY_RETRY).build();
        }
    }

    @Override
    public void queryMaterializedView(QueryMaterializedViewMessage request, StreamObserver<QueryMaterializedViewResponse> responseObserver) {
        responseObserver.onNext(queryMaterializedViewHandler(request));
        responseObserver.onCompleted();
    }

    private QueryMaterializedViewResponse queryMaterializedViewHandler(QueryMaterializedViewMessage m) {
        int shardNum = m.getShard();
        String name = m.getName();
        dataStore.createShardMetadata(shardNum);
        dataStore.shardLockMap.get(shardNum).readerLockLock();
        if (dataStore.consistentHash.getBucket(shardNum) == dataStore.dsID) {
            dataStore.ensureShardCached(shardNum);
            MaterializedView v = dataStore.materializedViewMap.get(shardNum).get(name);
            ByteString intermediate = v.getLatestView();
            dataStore.shardLockMap.get(shardNum).readerLockUnlock();
            return QueryMaterializedViewResponse.newBuilder().setReturnCode(Broker.QUERY_SUCCESS).setResponse(intermediate).build();
        } else {
            dataStore.shardLockMap.get(shardNum).readerLockUnlock();
            logger.warn("DS{} Got MV query for unassigned shard {} or name {}", dataStore.dsID, shardNum, name);
            return QueryMaterializedViewResponse.newBuilder().setReturnCode(Broker.QUERY_FAILURE).build();
        }
    }
}