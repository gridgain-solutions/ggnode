package com.gridgain.ignite.ggnode.cgrid;

import com.gridgain.ignite.ggnode.model.entities.Account;
import com.gridgain.ignite.ggnode.model.entities.AccountKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import javax.cache.Cache;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtLocalPartition;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.resources.IgniteInstanceResource;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * Finds all clients with their aggregate balances less than argument value.
 */
public class SumBalancesForAllClientsTask extends ComputeTaskAdapter<BigDecimal, Map<Long, BigDecimal>> {

    @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, BigDecimal arg) {
        return subgrid.stream()
            .collect(toMap(node -> new SumBalancesForAllClientsJob(arg), identity()));
    }

    @Override public Map<Long, BigDecimal> reduce(List<ComputeJobResult> results) throws IgniteException {
        Map<Long, BigDecimal> res = new HashMap<>();
        for (ComputeJobResult result : results) {
            Map<Long, BigDecimal> data = result.getData();

            res.putAll(data);
        }
        return res;
    }

    public static class SumBalancesForAllClientsJob extends ComputeJobAdapter {

        @IgniteInstanceResource
        private Ignite ignite;

        private BigDecimal threshold;

        public SumBalancesForAllClientsJob(BigDecimal threshold) {
            this.threshold = threshold;
        }

        @Override public Object execute() throws IgniteException {
            IgniteCache<BinaryObject, BinaryObject> cache = ignite.cache(Account.CACHE_NAME).withKeepBinary();
            ConcurrentHashMap<Long, BigDecimal> res = new ConcurrentHashMap<>();

            int[] parts = ignite.affinity(Account.CACHE_NAME).primaryPartitions(ignite.cluster().localNode());
            List<Future<?>> futs = new ArrayList<>();
            for (int part : parts) {
                QueryCursor<Cache.Entry<BinaryObject, BinaryObject>> cursor
                    = cache.query(new ScanQuery<BinaryObject, BinaryObject>(part).setLocal(true));

                futs.add(
                    ForkJoinPool.commonPool().submit(
                        () -> {
                            for (Cache.Entry<BinaryObject, BinaryObject> e : cursor) {
                                Long clientId = e.getKey().field("clientId");
                                BigDecimal balance = e.getValue().field("balance");

                                res.compute(
                                    clientId,
                                    (k, v) -> {
                                        if (v == null) return balance;
                                        else return v.add(balance);
                                    }
                                );
                            }
                        }
                    )
                );
            }

            for (Future<?> fut : futs) {
                try {
                    fut.get();
                } catch (Exception e) {
                    throw new IgniteException(e);
                }
            }

            if (threshold != null) {
                return res.entrySet().stream()
                    .filter(entry -> threshold.compareTo(entry.getValue()) > 0)
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            }

            return res;
        }
    }
}