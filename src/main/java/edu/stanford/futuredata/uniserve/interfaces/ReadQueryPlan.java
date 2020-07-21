package edu.stanford.futuredata.uniserve.interfaces;

import com.google.protobuf.ByteString;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

public interface ReadQueryPlan<S extends Shard, T> extends Serializable {
    /*
     Execute a read query.
     */

    // Which tables are being queried?
    List<String> getQueriedTables();
    // Shuffle tables on which column, if any?
    Optional<List<String>> getShuffleColumns();
    // Keys on which the query executes.  Query will execute on all shards containing any key from the list.
    // Include -1 to execute on all shards.
    List<Integer> keysForQuery();
    // This function will execute on each shard containing at least one key from keysForQuery.
    ByteString queryShard(List<S> shard);
    // For incrementally updated materialized views, query all rows whose timestamps satisfy startTime < t <= endTime
    ByteString queryShard(S shard, long startTime, long endTime); // TODO:  Have this handle multiple shards.
    // Combine multiple intermediates into one.
    ByteString combineIntermediates(List<ByteString> intermediates);
    // The query will return the result of this function executed on all results from queryShard.
    T aggregateShardQueries(List<ByteString> shardQueryResults);
    // Return an estimate of the cost of queryShard.  Always called after queryShard.
    int getQueryCost();
    // Get query plans for subqueries.
    List<ReadQueryPlan> getSubQueries();
    // Set results of subqueries.
    void setSubQueryResults(List<Object> subQueryResults);
}
