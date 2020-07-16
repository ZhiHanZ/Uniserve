package edu.stanford.futuredata.uniserve.utilities;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConsistentHash implements Serializable {

    private final List<Integer> hashRing = new ArrayList<>();
    private final Map<Integer, Integer> hashToBucket = new HashMap<>();

    private static final int numVirtualNodes = 10;
    private static final int virtualOffset = 1234567;

    private static final double A = (Math.sqrt(5) - 1) / 2;
    private static final int m = 2147483647; // 2 ^ 31 - 1

    private static final ReadWriteLock lock = new ReentrantReadWriteLock();

    private int hashFunction(int k) {
        // from CLRS, including the magic numbers.
        return (int) (m * (k * A - Math.floor(k * A)));
    }

    public void addBucket(int bucketNum) {
        lock.writeLock().lock();
        for(int i = 0; i < numVirtualNodes; i++) {
            int hash = hashFunction(bucketNum + virtualOffset * i);
            hashRing.add(hash);
            hashToBucket.put(hash, bucketNum);
        }
        Collections.sort(hashRing);
        lock.writeLock().unlock();
    }

    public void removeBucket (int bucketNum) {
        lock.writeLock().lock();
        for(int i = 0; i < numVirtualNodes; i++) {
            int hash = hashFunction(bucketNum + virtualOffset * i);
            hashRing.remove(hash);
            hashToBucket.remove(hash);
        }
        Collections.sort(hashRing);
        lock.writeLock().unlock();
    }

    public int getBucket(int key) {
        lock.readLock().lock();
        int hash = hashFunction(key);
        for (int n : hashRing) {
            if (hash < n) {
                int ret = hashToBucket.get(n);
                lock.readLock().unlock();
                return ret;
            }
        }
        int ret = hashToBucket.get(hashRing.get(0));
        lock.readLock().unlock();
        return ret;
    }

}