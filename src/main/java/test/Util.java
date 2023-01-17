package test;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.activemq.broker.region.policy.IndividualDeadLetterStrategy;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.store.kahadb.KahaDBPersistenceAdapter;
import org.apache.activemq.store.kahadb.disk.journal.Journal.JournalDiskSyncStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.ConnectionFactory;
import java.net.InetAddress;
import java.net.URI;

/**
 * @author Endre Stølsvik 2023-01-14 17:56 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Util {

    private static final Logger log = LoggerFactory.getLogger(Util.class);

    static BrokerAndConnectionFactory createBroker(JournalDiskSyncStrategy syncStrategy) throws Exception {
        // :: CREATING BROKER
        BrokerService brokerService = new BrokerService();
        // brokerService.setBrokerName(BROKER_NAME);
        // Do persistence-adaptor
        if (syncStrategy != null) {
            KahaDBPersistenceAdapter kahaDBPersistenceAdapter = new KahaDBPersistenceAdapter();
            kahaDBPersistenceAdapter.setJournalDiskSyncStrategy(syncStrategy.name());
            // NOTE: This following value is default 1000, i.e. sync interval of 1 sec.
            // Interestingly, setting it to a much lower value, e.g. 10 or 25, seemingly doesn't severely impact
            // performance of the PERIODIC strategy. Thus, instead of potentially losing a full second's worth of
            // messages if someone literally pulled the power cord of the ActiveMQ instance, you'd lose much less.
            kahaDBPersistenceAdapter.setJournalDiskSyncInterval(25);
            brokerService.setPersistenceAdapter(kahaDBPersistenceAdapter);
        }
        else {
            brokerService.setPersistent(false);
        }


        // Make this as network-realistic as possible
        String hostname = InetAddress.getLocalHost().getHostName();

        try {
            TransportConnector connector = new TransportConnector();
            connector.setUri(new URI("nio://" + hostname + ":61617"));
            brokerService.addConnector(connector);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // :: Set Individual DLQ - which you most definitely should do in production.
        // Hear, hear: https://users.activemq.apache.narkive.com/H7400Mn1/policymap-api-is-really-bad
        IndividualDeadLetterStrategy individualDeadLetterStrategy = new IndividualDeadLetterStrategy();
        individualDeadLetterStrategy.setQueuePrefix("DLQ.");
        individualDeadLetterStrategy.setTopicPrefix("DLQ.");
        // .. Send expired messages to DLQ (Note: true is default)
        individualDeadLetterStrategy.setProcessExpired(true);
        // .. Also DLQ non-persistent messages
        individualDeadLetterStrategy.setProcessNonPersistent(true);
        individualDeadLetterStrategy.setUseQueueForTopicMessages(true); // true is also default
        individualDeadLetterStrategy.setUseQueueForQueueMessages(true); // true is also default.

        // :: Create destination policy entry for QUEUES:
        PolicyEntry allQueuesPolicy = new PolicyEntry();
        allQueuesPolicy.setDestination(new ActiveMQQueue(">")); // all queues
        // .. add the IndividualDeadLetterStrategy
        allQueuesPolicy.setDeadLetterStrategy(individualDeadLetterStrategy);
        // .. we do use prioritization, and this should ensure that priority information is handled in queue, and
        // persisted to store. Store JavaDoc: "A hint to the store to try recover messages according to priority"
        allQueuesPolicy.setPrioritizedMessages(true);
        allQueuesPolicy.setUseCache(false);
        // Purge inactive Queues. The set of Queues should really be pretty stable. We only want to eventually
        // get rid of queues for Endpoints which are taken out of the codebase.
        allQueuesPolicy.setGcInactiveDestinations(true);
        allQueuesPolicy.setInactiveTimeoutBeforeGC(2 * 24 * 60 * 60 * 1000); // Two full days.

        // :: Create policy entry for TOPICS:
        PolicyEntry allTopicsPolicy = new PolicyEntry();
        allTopicsPolicy.setDestination(new ActiveMQTopic(">")); // all topics
        // .. add the IndividualDeadLetterStrategy, not sure if that is ever relevant for plain Topics.
        allTopicsPolicy.setDeadLetterStrategy(individualDeadLetterStrategy);
        // .. and prioritization, not sure if that is ever relevant for Topics.
        allTopicsPolicy.setPrioritizedMessages(true);

        // Purge inactive Topics. The names of Topics will often end up being host-specific. The utility
        // MatsFuturizer uses such logic. When using Kubernetes, the pods will change name upon redeploy of
        // services. Get rid of the old pretty fast. But we want to see them in destination browsers like
        // MatsBrokerMonitor, so not too fast.
        allTopicsPolicy.setGcInactiveDestinations(true);
        allTopicsPolicy.setInactiveTimeoutBeforeGC(2 * 60 * 60 * 1000); // 2 hours.
        // .. note: Not leveraging the SubscriptionRecoveryPolicy features, as we do not have evidence of this being
        // a problem, and using it does incur a cost wrt. memory and time.
        // Would probably have used a FixedSizedSubscriptionRecoveryPolicy, with setUseSharedBuffer(true).
        // To actually get subscribers to use this, one would have to also set the client side (consumer) to be
        // 'Retroactive Consumer' i.e. new ActiveMQTopic("TEST.Topic?consumer.retroactive=true"); This is not
        // done by the JMS impl of Mats.
        // https://activemq.apache.org/retroactive-consumer

        /*
         * .. chill the prefetch a bit, from Queue:1000 and Topic:Short.MAX_VALUE (!), which is very much when used
         * with Mats and its transactional logic of "consume a message, produce a message, commit", as well as
         * multiple StageProcessors per Stage, on multiple instances/replicas of the services. Lowering this
         * considerably to instead focus on lower memory usage, good distribution, and if one consumer by any chance
         * gets hung, it won't allocate so many of the messages into a "void". This can be set on client side, but
         * if not set there, it gets the defaults from server, AFAIU.
         */
        allQueuesPolicy.setQueuePrefetch(5000);
        allTopicsPolicy.setTopicPrefetch(1000);

        // .. create the PolicyMap containing the two destination policies
        PolicyMap policyMap = new PolicyMap();
        policyMap.put(allQueuesPolicy.getDestination(), allQueuesPolicy);
        policyMap.put(allTopicsPolicy.getDestination(), allTopicsPolicy);
        // .. set this PolicyMap containing our PolicyEntry on the broker.
        brokerService.setDestinationPolicy(policyMap);

        // No need for JMX registry.
        brokerService.setUseJmx(false);
        // No need for Advisory Messages.
        brokerService.setAdvisorySupport(false);
        // We'll shut it down ourselves.
        brokerService.setUseShutdownHook(false);
        // Start it
        brokerService.start();

        log.info("getVmConnectorURI: " + brokerService.getVmConnectorURI());
        log.info("getDefaultSocketURIString: " + brokerService.getDefaultSocketURIString());
//        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerService.getVmConnectorURI());
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerService.getDefaultSocketURIString());
//        ActiveMQPrefetchPolicy prefetchPolicy = new ActiveMQPrefetchPolicy();
//        prefetchPolicy.setQueuePrefetch(16);
//        connectionFactory.setPrefetchPolicy(prefetchPolicy);

        return new BrokerAndConnectionFactoryActiveMqImpl(brokerService, connectionFactory);
    }


    public interface BrokerAndConnectionFactory {
        void closeBroker();

        ConnectionFactory getConnectionFactory();
    }


    public static class BrokerAndConnectionFactoryActiveMqImpl implements BrokerAndConnectionFactory {
        private final BrokerService _brokerService;
        private final ConnectionFactory _connectionFactory;

        public BrokerAndConnectionFactoryActiveMqImpl(BrokerService brokerService,
                ConnectionFactory connectionFactory) {
            _brokerService = brokerService;
            _connectionFactory = connectionFactory;
        }

        @Override
        public void closeBroker() {
            try {
                _brokerService.stop();
            }
            catch (Exception e) {
                throw new IllegalStateException("Could not stop.", e);
            }
        }

        @Override
        public ConnectionFactory getConnectionFactory() {
            return _connectionFactory;
        }
    }

    static void takeNap(int ms) {
        try {
            Thread.sleep(ms);
        }
        catch (InterruptedException e) {
            throw new IllegalStateException("Got interrupted.", e);
        }
    }

}
