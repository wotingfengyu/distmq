package com.github.wenweihu86.distmq.client.zk;

import com.github.wenweihu86.distmq.client.BrokerClient;
import com.github.wenweihu86.distmq.client.BrokerClientManager;
import com.github.wenweihu86.distmq.client.consumer.ConsumerConfig;
import com.github.wenweihu86.distmq.client.producer.ProducerConfig;
import com.github.wenweihu86.rpc.client.RPCClientOptions;
import org.apache.commons.collections.CollectionUtils;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by wenweihu86 on 2017/6/21.
 */
public class ZKClient {
    private static final Logger LOG = LoggerFactory.getLogger(ZKClient.class);
    private ZKConf zkConf;
    private CuratorFramework zkClient;
    private boolean isProducer = false;
    private boolean isConsumer = false;

    public ZKClient(ZKConf conf) {
        this.zkConf = conf;
        if (conf instanceof ProducerConfig) {
            isProducer = true;
        } else if (conf instanceof ConsumerConfig) {
            isConsumer = true;
        }
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(
                zkConf.getRetryIntervalMs(), zkConf.getRetryCount());
        this.zkClient = CuratorFrameworkFactory.builder()
                .connectString(zkConf.getServers())
                .retryPolicy(retryPolicy)
                .connectionTimeoutMs(zkConf.getConnectTimeoutMs())
                .sessionTimeoutMs(zkConf.getSessionTimeoutMs())
                .build();
        this.zkClient.start();
    }

    public void registerBroker(int shardingId, String ip, int port) {
        String path = String.format("%s/brokers/%d/%s:%d",
                zkConf.getBasePath(), shardingId, ip, port);
        try {
            zkClient.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(path, "".getBytes());
        } catch (Exception ex) {
            LOG.warn("registerBroker exception:", ex);
        }
        LOG.info("register broker sucess, ip={}, port={}", ip, port);
    }

    // 启动时调用，所以不用加锁
    public void subscribeBroker() {
        final ZKData zkData = ZKData.getInstance();
        final Map<Integer, List<String>> brokerMap = zkData.getBrokerMap();
        try {
            final String brokerParentPath = zkConf.getBasePath() + "/brokers";
            List<String> shardings = zkClient.getChildren().forPath(brokerParentPath);
            for (String sharding : shardings) {
                final int shardingId = Integer.valueOf(sharding);
                final String shardingPath = brokerParentPath + "/" + sharding;
                List<String> brokerAddressList = zkClient.getChildren().forPath(shardingPath);
                if (isProducer || isConsumer) {
                    RPCClientOptions rpcClientOptions = BrokerClientManager.getRpcClientOptions();
                    for (String address : brokerAddressList) {
                        BrokerClient brokerClient = new BrokerClient(address, rpcClientOptions);
                        BrokerClientManager.getInstance().getBrokerClientMap().put(address, brokerClient);
                    }
                }
                brokerMap.put(shardingId, brokerAddressList);
                // 监听broker分片变化
                zkClient.getChildren().usingWatcher(
                        new BrokerShardingWather(shardingId))
                        .forPath(shardingPath);
            }
            // 监听/brokers孩子节点变化
            zkClient.getChildren().usingWatcher(new BrokersWatcher()).forPath(brokerParentPath);
        } catch (Exception ex) {
            LOG.warn("subscribeBroker exception:", ex);
        }
    }

    /**
     * 创建新的topic，加锁是防止重复创建
     * @param topic topic名称
     * @param queueNum queue个数
     */
    public synchronized void registerTopic(String topic, int queueNum) {
        ZKData zkData = ZKData.getInstance();
        List<Integer> shardingIds;
        zkData.getBrokerLock().lock();
        try {
            Map<Integer, List<String>> brokerMap = zkData.getBrokerMap();
            shardingIds = new ArrayList<>(brokerMap.keySet());
        } finally {
            zkData.getBrokerLock().unlock();
        }

        int shardingNum = shardingIds.size();
        String topicPath = zkConf.getBasePath() + "/topics/" + topic;
        for (int queueId = 0; queueId < queueNum; queueId++) {
            int index = queueId % shardingNum;
            int shardingId = shardingIds.get(index);
            String queuePath = topicPath + "/" + queueId;
            byte[] queueData = String.valueOf(shardingId).getBytes();
            try {
                zkClient.create()
                        .creatingParentsIfNeeded()
                        .forPath(queuePath, queueData);
            } catch (Exception ex) {
                LOG.warn("registerTopic exception:", ex);
            }
        }
    }

    // 启动时调用，所以不用加锁
    public void subscribeTopic() {
        ZKData zkData = ZKData.getInstance();
        Map<String, Map<Integer, Integer>> topicMap = zkData.getTopicMap();
        String topicParentPath = zkConf.getBasePath() + "/topics/";
        try {
            List<String> topics = zkClient.getChildren().forPath(topicParentPath);
            for (String topic : topics) {
                Map<Integer, Integer> queueMap = topicMap.get(topic);
                if (queueMap == null) {
                    queueMap = new HashMap<>();
                    topicMap.put(topic, queueMap);
                }
                String topicPath = topicParentPath + "/" + topic;
                List<String> queues = zkClient.getChildren().forPath(topicPath);
                for (String queue : queues) {
                    String queuePath = topicPath + "/" + queue;
                    String queueData = new String(zkClient.getData().forPath(queuePath));
                    Integer shardingId = Integer.valueOf(queueData);
                    Integer queueId = Integer.valueOf(queue);
                    queueMap.put(queueId, shardingId);
                }
                // 监听topic下的queue变化事件
                // 这里假定queue与shardingId映射关系不会发生变化，所以没有监听queue节点变化事情
                zkClient.getChildren().usingWatcher(
                        new TopicWatcher(topic))
                        .forPath(topicPath);
            }
            // 监听/topics孩子节点变化情况
            zkClient.getChildren().usingWatcher(
                    new TopicsWather())
                    .forPath(topicParentPath);
        } catch (Exception ex) {
            LOG.warn("subscribeTopic exception:", ex);
        }
    }

    public void registerConsumer(String consumerGroup, String consumerId) {
        String path = zkConf.getBasePath() + "/consumers/" + consumerGroup + "/ids/" + consumerId;
        try {
            zkClient.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(path, "".getBytes());
        } catch (Exception ex) {
            LOG.warn("registerConsumer exception:", ex);
        }
        LOG.info("registerConsumer sucess, consumerGroup={}, consumerId={}", consumerGroup, consumerId);
    }

    public void subscribeConsumer(String consumerGroup) {
        ZKData zkData = ZKData.getInstance();
        String path = zkConf.getBasePath() + "/consumers/" + consumerGroup + "/ids";
        try {
            List<String> consumerIds = zkClient.getChildren().forPath(path);
            zkData.setConsumerIds(consumerIds);
            zkClient.getChildren()
                    .usingWatcher(new ConsumerWatcher(consumerGroup))
                    .forPath(path);
        } catch (Exception ex) {
            LOG.warn("subscribeConsumer exception:", ex);
        }
    }

    public long readConsumerOffset(String consumerGroup, String topic) {
        long offset = 0;
        String path = zkConf.getBasePath() + "/consumers/" + consumerGroup + "/offsets/" + topic;
        try {
            byte[] dataBytes = zkClient.getData().forPath(path);
            if (dataBytes != null) {
                offset = Long.valueOf(new String(dataBytes));
            }
        } catch (Exception ex) {
            LOG.warn("readConsumerOffset exception:", ex);
        }
        return offset;
    }

    public void updateConsumerOffset(String consumerGroup, String topic, long offset) {
        String path = zkConf.getBasePath() + "/consumers/" + consumerGroup + "/offsets/" + topic;
        try {
            byte[] dataBytes = String.valueOf(offset).getBytes();
            zkClient.setData().forPath(path, dataBytes);
        } catch (Exception ex) {
            LOG.warn("updateConsumerOffset exception:", ex);
        }
    }

    private class ConsumerWatcher implements CuratorWatcher {
        private String consumerGroup;

        public ConsumerWatcher(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        @Override
        public void process(WatchedEvent event) throws Exception {
            if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                String path = zkConf.getBasePath() + "/consumers/" + consumerGroup + "/ids";
                try {
                    List<String> consumerIds = zkClient.getChildren().forPath(path);
                    ZKData zkData = ZKData.getInstance();
                    zkData.setConsumerIds(consumerIds);
                } catch (Exception ex) {
                    LOG.warn("subscribeConsumer exception:", ex);
                }
            }
        }
    }

    // 监听所有broker的分片信息变化事件
    private class BrokersWatcher implements CuratorWatcher {
        @Override
        public void process(WatchedEvent event) throws Exception {
            ZKData zkData = ZKData.getInstance();
            Map<Integer, List<String>> brokerMap = zkData.getBrokerMap();
            if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                String brokerPath = zkConf.getBasePath() + "/brokers";
                List<String> newShardings = zkClient.getChildren().forPath(brokerPath);

                List<String> oldShardings = new ArrayList<>();
                zkData.getBrokerLock().lock();
                try {
                    for (Integer shardingId : zkData.getBrokerMap().keySet()) {
                        oldShardings.add(String.valueOf(shardingId));
                    }
                } finally {
                    zkData.getBrokerLock().unlock();
                }

                Collection<String> addedShardings = CollectionUtils.subtract(newShardings, oldShardings);
                Collection<String> deletedShardings = CollectionUtils.subtract(oldShardings, newShardings);
                for (String sharding : addedShardings) {
                    int shardingId = Integer.valueOf(sharding);
                    String shardingPath = brokerPath + "/" + sharding;
                    List<String> brokerAddressList = zkClient.getChildren().forPath(shardingPath);
                    for (String address : brokerAddressList) {
                        if (isProducer || isConsumer) {
                            BrokerClient brokerClient = new BrokerClient(address, BrokerClientManager.getRpcClientOptions());
                            BrokerClientManager.getInstance().getBrokerClientMap().putIfAbsent(address, brokerClient);
                        }
                    }

                    zkData.getBrokerLock().lock();
                    try {
                        zkData.getBrokerMap().put(shardingId, brokerAddressList);
                    } finally {
                        zkData.getBrokerLock().unlock();
                    }

                    zkClient.getChildren().usingWatcher(
                            new BrokerShardingWather(shardingId))
                            .forPath(shardingPath);
                }

                for (String sharding : deletedShardings) {
                    List<String> brokerList;
                    zkData.getBrokerLock().lock();
                    try {
                        brokerList = zkData.getBrokerMap().remove(Integer.valueOf(sharding));
                    } finally {
                        zkData.getBrokerLock().unlock();
                    }
                    if ((isProducer || isConsumer) && CollectionUtils.isNotEmpty(brokerList)) {
                        ConcurrentMap<String, BrokerClient> brokerClientMap
                                = BrokerClientManager.getInstance().getBrokerClientMap();
                        for (String address : brokerList) {
                            BrokerClient client = brokerClientMap.get(address);
                            client.getRpcClient().stop();
                            brokerClientMap.remove(address);
                        }
                    }
                }
            }
        }
    }

    // 监听broker某个分片下的节点变化
    private class BrokerShardingWather implements CuratorWatcher {
        private int shardingId;

        public BrokerShardingWather(int shardingId) {
            this.shardingId = shardingId;
        }

        @Override
        public void process(WatchedEvent event) throws Exception {
            ZKData zkData = ZKData.getInstance();
            Map<Integer, List<String>> brokerMap = zkData.getBrokerMap();
            if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                String shardingPath = zkConf.getBasePath() + "/brokers/" + shardingId;
                List<String> newBrokerAddressList = zkClient.getChildren().forPath(shardingPath);
                List<String> oldBrokerAddressList;
                zkData.getBrokerLock().lock();
                try {
                    oldBrokerAddressList = zkData.getBrokerMap().get(shardingPath);
                } finally {
                    zkData.getBrokerLock().unlock();
                }
                Collection<String> addedBrokerAddressList
                        = CollectionUtils.subtract(newBrokerAddressList, oldBrokerAddressList);
                Collection<String> deletedBrokerAddressList
                        = CollectionUtils.subtract(oldBrokerAddressList, newBrokerAddressList);
                for (String address : addedBrokerAddressList) {
                    zkData.getBrokerLock().lock();
                    try {
                        zkData.getBrokerMap().get(shardingId).add(address);
                    } finally {
                        zkData.getBrokerLock().unlock();
                    }

                    if (isProducer || isConsumer) {
                        BrokerClient brokerClient = new BrokerClient(address, BrokerClientManager.getRpcClientOptions());
                        BrokerClientManager.getInstance().getBrokerClientMap().putIfAbsent(address, brokerClient);
                    }
                }
                for (String address : deletedBrokerAddressList) {
                    zkData.getBrokerLock().lock();
                    try {
                        zkData.getBrokerMap().get(shardingId).remove(address);
                    } finally {
                        zkData.getBrokerLock().unlock();
                    }

                    if (isProducer || isConsumer) {
                        BrokerClient brokerClient
                                = BrokerClientManager.getInstance().getBrokerClientMap().remove(address);
                        brokerClient.getRpcClient().stop();
                    }
                }
            }
        }
    }

    // 监听所有topic的更变
    private class TopicsWather implements CuratorWatcher {
        @Override
        public void process(WatchedEvent event) throws Exception {
            ZKData zkData = ZKData.getInstance();
            if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                String topicParentPath = zkConf.getBasePath() + "/topics";
                List<String> newTopics = zkClient.getChildren().forPath(topicParentPath);
                List<String> oldTopics;
                zkData.getTopicLock().lockInterruptibly();
                try {
                    oldTopics = new ArrayList<>(zkData.getTopicMap().keySet());
                } finally {
                    zkData.getTopicLock().unlock();
                }
                Collection<String> addedTopics = CollectionUtils.subtract(newTopics, oldTopics);
                Collection<String> deletedTopics = CollectionUtils.subtract(oldTopics, newTopics);
                for (String topic : addedTopics) {
                    Map<Integer, Integer> queueMap = new HashMap<>();
                    String topicPath = topicParentPath + "/" + topic;
                    List<String> queueList = zkClient.getChildren().forPath(topicPath);
                    for (String queue : queueList) {
                        String queuePath = topicPath + "/" + queue;
                        String queueData = new String(zkClient.getData().forPath(queuePath));
                        int shardingId = Integer.valueOf(queueData);
                        int queueId = Integer.valueOf(queue);
                        queueMap.put(queueId, shardingId);
                    }
                    zkClient.getChildren().usingWatcher(
                            new TopicWatcher(topic))
                            .forPath(topicPath);
                    zkData.getTopicLock().lockInterruptibly();
                    try {
                        zkData.getTopicMap().put(topic, queueMap);
                        zkData.getTopicCondition().signalAll();
                    } finally {
                        zkData.getTopicLock().unlock();
                    }
                }

                zkData.getTopicLock().lockInterruptibly();
                try {
                    for (String topic : deletedTopics) {
                        zkData.getTopicMap().remove(topic);
                        // TODO: is need remove watcher?
                    }
                } finally {
                    zkData.getTopicLock().unlock();
                }
            }
        }
    }

    // 监听具体某个topic下queue的变更
    private class TopicWatcher implements CuratorWatcher {
        private String topic;

        public TopicWatcher(String topic) {
            this.topic = topic;
        }

        @Override
        public void process(WatchedEvent event) throws Exception {
            ZKData zkData = ZKData.getInstance();
            if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                String topicPath = zkConf.getBasePath() + "/topics/" + topic;
                List<String> newQueues = zkClient.getChildren().forPath(topicPath);
                List<Integer> newQueueIds = new ArrayList<>();
                for (String queue : newQueues) {
                    newQueueIds.add(Integer.valueOf(queue));
                }

                List<Integer> oldQueueIds;
                zkData.getTopicLock().lockInterruptibly();
                try {
                    oldQueueIds = new ArrayList<>(zkData.getTopicMap().get(topic).keySet());
                } finally {
                    zkData.getTopicLock().unlock();
                }

                Collection<Integer> addedQueueIds = CollectionUtils.subtract(newQueueIds, oldQueueIds);
                Collection<Integer> deletedQueueIds = CollectionUtils.subtract(oldQueueIds, newQueueIds);
                for (Integer queueId : addedQueueIds) {
                    String queuePath = topicPath + "/" + queueId;
                    String queueData = new String(zkClient.getData().forPath(queuePath));
                    Integer shardingId = Integer.valueOf(queueData);

                    zkData.getTopicLock().lockInterruptibly();
                    try {
                        zkData.getTopicMap().get(topic).put(queueId, shardingId);
                    } finally {
                        zkData.getTopicLock().unlock();
                    }
                }
                zkData.getTopicLock().lockInterruptibly();
                try {
                    for (Integer queueId : deletedQueueIds) {
                        zkData.getTopicMap().get(topic).remove(queueId);
                    }
                } finally {
                    zkData.getTopicLock().unlock();
                }
            }
        }
    }

}
