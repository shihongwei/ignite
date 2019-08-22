/*
 * Copyright 2019 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.console.web.socket;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.cache.Cache;
import org.apache.ignite.Ignite;
import org.apache.ignite.console.db.CacheHolder;
import org.apache.ignite.console.db.OneToManyIndex;
import org.apache.ignite.console.tx.TransactionManager;
import org.apache.ignite.console.websocket.TopologySnapshot;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.StreamSupport.stream;


/**
 * Clusters index repository.
 */
@Repository
public class ClustersRepository {
    /** Default for cluster topology expire. */
    private static final long DEFAULT_CLUSTER_CLEANUP = MINUTES.toMillis(10);

    /** */
    private static final Logger log = LoggerFactory.getLogger(ClustersRepository.class);

    /** */
    protected Ignite ignite;

    /** */
    protected TransactionManager txMgr;

    /** */
    private CacheHolder<String, TopologySnapshot> clusters;

    /** */
    private OneToManyIndex<UserKey, ClusterSession> clusterIdsByBrowser;

    /**
     * @param ignite Ignite.
     * @param txMgr Tx manager.
     */
    public ClustersRepository(Ignite ignite, TransactionManager txMgr) {
        this.ignite = ignite;
        this.txMgr = txMgr;


        this.txMgr.registerStarter(() -> {
            clusters = new CacheHolder<>(ignite, "wc_clusters");
            clusterIdsByBrowser = new OneToManyIndex<>(ignite, "wc_clusters_idx");

            cleanupClusterIndex();
        });
    }

    /**
     * Get latest topology for user
     *
     * @param user User.
     */
    public Set<TopologySnapshot> get(UserKey user) {
        return txMgr.doInTransaction(() ->
            Optional.ofNullable(clusterIdsByBrowser.get(user))
                .orElseGet(Collections::emptySet).stream()
                .map(ClusterSession::getClusterId)
                .distinct()
                .map(this.clusters::get)
                .collect(toSet())
        );
    }

    /**
     * Get latest topology for clusters
     *
     * @param clusterIds Cluster ids.
     */
    public Set<TopologySnapshot> get(Set<String> clusterIds) {
        return txMgr.doInTransaction(() -> clusterIds.stream().map(clusters::get).collect(toSet()));
    }

    /**
     * Find cluster with same topology and get it's cluster id.
     * 
     * @param top Topology.
     */
    public String findClusterId(TopologySnapshot top) {
        return txMgr.doInTransaction(() ->
            stream(this.clusters.cache().spliterator(), false)
                .filter(e -> e.getValue().sameNodes(top))
                .map(Cache.Entry::getKey)
                .findFirst()
                .orElse(null)
        );
    }

    /**
     * Save topology in cache
     *
     * @param accIds Account ids.
     * @param top Topology.
     */
    public TopologySnapshot getAndPut(Set<UUID> accIds, TopologySnapshot top) {
        UUID nid = ignite.cluster().localNode().id();

        return txMgr.doInTransaction(() -> {
            ClusterSession clusterSes = new ClusterSession(nid, top.getId());

            for (UUID accId : accIds)
                clusterIdsByBrowser.add(new UserKey(accId, top.isDemo()), clusterSes);

            return clusters.getAndPut(top.getId(), top);
        });
    }

    /**
     * Remove cluster from local backend
     *
     * @param accId Acc id.
     * @param clusterId Cluster id.
     */
    public void remove(UUID accId, String clusterId) {
        UUID nid = ignite.cluster().localNode().id();

        ClusterSession clusterSes = new ClusterSession(nid, clusterId);

        txMgr.doInTransaction(() -> {
            boolean demo = clusters.get(clusterId).isDemo();

            clusterIdsByBrowser.remove(new UserKey(accId, demo), clusterSes);
        });
    }

    /**
     * Has demo for account
     *
     * @param accId Account id.
     */
    public boolean hasDemo(UUID accId) {
        return !F.isEmpty(clusterIdsByBrowser.get(new UserKey(accId, true)));
    }

    /**
     * Cleanup cluster index.
     */
    void cleanupClusterIndex() {
        Collection<UUID> nids = U.nodeIds(ignite.cluster().nodes());

        stream(clusterIdsByBrowser.cache().spliterator(), false)
            .peek(entry -> {
                Set<ClusterSession> activeClusters =
                    entry.getValue().stream().filter(cluster -> nids.contains(cluster.getNid())).collect(toSet());

                entry.getValue().removeAll(activeClusters);
            })
            .filter(entry -> !entry.getValue().isEmpty())
            .forEach(entry -> clusterIdsByBrowser.removeAll(entry.getKey(), entry.getValue()));
    }

    /**
     * Periodically cleanup expired cluster topology.
     */
    @Scheduled(initialDelay = 0, fixedRate = 60_000)
    public void cleanupClusterHistory() {
        txMgr.doInTransaction(() -> {
            Set<String> clusterIds = stream(this.clusters.cache().spliterator(), false)
                .filter(entry -> entry.getValue().isExpired(DEFAULT_CLUSTER_CLEANUP))
                .map(Cache.Entry::getKey)
                .collect(toSet());

            if (!F.isEmpty(clusterIds)) {
                clusters.cache().removeAll(clusterIds);

                log.debug("Failed to receive topology update for clusters: " + clusterIds);
            }
        });
    }
}