package test;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.store.kahadb.KahaDBPersistenceAdapter;
import org.apache.activemq.store.kahadb.disk.journal.Journal.JournalDiskSyncStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author Endre Stølsvik 2022-01-26 01:07 - http://stolsvik.com/, endre@stolsvik.com
 */
public class BrokerTest {

    private static final Logger log = LoggerFactory.getLogger(BrokerTest.class);

    private static final String TEST_QUEUE = "TestQueue";
    private static final String BROKER_NAME = "TestBroker";

    public static void main(String... args) throws Exception {
        BrokerTest brokerTest = new BrokerTest();
        brokerTest.runTest();
    }

    private static final int NUM_MESSAGES_MAIN = 1000;

    public void runTest() throws Exception {
        int multiple = 1;
        // WARMUP
        runTest(NUM_MESSAGES_MAIN * multiple, null, false, false);
        runTest(NUM_MESSAGES_MAIN, null, false, true);
        runTest(NUM_MESSAGES_MAIN, null, true, false);
        runTest(NUM_MESSAGES_MAIN, null, true, true);
        runTest(NUM_MESSAGES_MAIN * multiple, JournalDiskSyncStrategy.ALWAYS, false, false);
        runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.ALWAYS, false, true);
        runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.ALWAYS, true, false);
        runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.ALWAYS, true, true);
        runTest(NUM_MESSAGES_MAIN * multiple, JournalDiskSyncStrategy.PERIODIC, false, false);
        runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.PERIODIC, false, true);
        runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.PERIODIC, true, false);
        runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.PERIODIC, true, true);

        // MEASURE
        // Note: The non-Transactional & non-Persistent are so fast that we increase the number of messages.

        double nnn = runTest(NUM_MESSAGES_MAIN * multiple, null, false, false);
        double nnp = runTest(NUM_MESSAGES_MAIN, null, false, true);
        double ntn = runTest(NUM_MESSAGES_MAIN, null, true, false);
        double ntp = runTest(NUM_MESSAGES_MAIN, null, true, true);
        double ann = runTest(NUM_MESSAGES_MAIN * multiple, JournalDiskSyncStrategy.ALWAYS, false, false);
        double anp = runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.ALWAYS, false, true);
        double atn = runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.ALWAYS, true, false);
        double atp = runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.ALWAYS, true, true);
        double pnn = runTest(NUM_MESSAGES_MAIN * multiple, JournalDiskSyncStrategy.PERIODIC, false, false);
        double pnp = runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.PERIODIC, false, true);
        double ptn = runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.PERIODIC, true, false);
        double ptp = runTest(NUM_MESSAGES_MAIN, JournalDiskSyncStrategy.PERIODIC, true, true);
        nnn = nnn / multiple;
        ann = ann / multiple;
        pnn = pnn / multiple;
        log.info("Each test is time for sending and in case of 'Transactional', committing, [" + NUM_MESSAGES_MAIN + "] messages");
        log.info("no store, non-Transactional, non-Persistent:   [" + nnn + "] ms");
        log.info("no store, non-Transactional, Persistent:   [" + nnp + "] ms");
        log.info("no store, Transactional, non-Persistent:   [" + ntn + "] ms");
        log.info("no store, Transactional, Persistent:   [" + ntp + "] ms");
        log.info("ALWAYS, non-Transactional, non-Persistent:   [" + ann + "] ms");
        log.info("ALWAYS, non-Transactional, Persistent:   [" + anp + "] ms");
        log.info("ALWAYS, Transactional, non-Persistent:   [" + atn + "] ms");
        log.info("ALWAYS, Transactional, Persistent:   [" + atp + "] ms");
        log.info("PERIODIC, non-Transactional, non-Persistent:   [" + pnn + "] ms");
        log.info("PERIODIC, non-Transactional, Persistent:   [" + pnp + "] ms");
        log.info("PERIODIC, Transactional, non-Persistent:   [" + ptn + "] ms");
        log.info("PERIODIC, Transactional, Persistent:   [" + ptp + "] ms");
    }

    private double runTest(int numMessages, JournalDiskSyncStrategy syncStrategy, boolean transactional,
            boolean persistent) throws Exception {
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory("vm://" + BROKER_NAME);

        // :: CREATING BROKER
        BrokerService brokerService = new BrokerService();
        brokerService.setBrokerName(BROKER_NAME);
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
        // No need for JMX registry.
        brokerService.setUseJmx(false);
        // No need for Advisory Messages.
        brokerService.setAdvisorySupport(false);
        // We'll shut it down ourselves.
        brokerService.setUseShutdownHook(false);
        // Start it
        brokerService.start();

        // :: CREATE CONSUME MESSAGES THREAD
        CountDownLatch latch = new CountDownLatch(1);
        Thread receiveThread = new Thread(() -> {
            this.receiveMessagesRunner(connectionFactory, numMessages, latch);
        }, "TestReceiver:" + syncStrategy + ";trans=" + transactional + ";persist=" + persistent);
        receiveThread.start();

        // :: RUN TEST: Send a bunch of messages
        double millisTaken = sendBunchOfMessages(connectionFactory, numMessages, transactional, persistent);
        log.info("Done sending");

        // Chill a tad to receive the last message, then interrupt
        boolean latched = latch.await(10, TimeUnit.SECONDS);
        if (!latched) {
            throw new AssertionError("Didn't received expected number [" + numMessages + "] of messages");
        }

        brokerService.stop();
        return millisTaken;
    }

    private double sendBunchOfMessages(ConnectionFactory connectionFactory, int numMessages, boolean transactional,
            boolean persistent) throws JMSException {
        Connection connection = connectionFactory.createConnection();
        connection.start();
        Session session = transactional
                ? connection.createSession(true, Session.SESSION_TRANSACTED)
                : connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue(TEST_QUEUE);
        MessageProducer producer = session.createProducer(queue);
        // NOTICE: It does NOT matter whether an individual message is set NON_PERSISTENT.
        // Only the producer's DeliveryMode counts.
        // !! Try commenting out this line, which really shouldn't have changed the semantics
        producer.setDeliveryMode(persistent ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT);
        log.info("============ Starting sending of [" + numMessages + "] messages");
        long nanosStart_send = System.nanoTime();
        for (int i = 0; i < numMessages; i++) {
            TextMessage textMsg = session.createTextMessage();
            textMsg.setText("Text:" + i);

            // NOTICE: It does NOT matter whether an individual message is set NON_PERSISTENT.
            // Only the producer's DeliveryMode counts.
            textMsg.setJMSDeliveryMode(persistent ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT);
            producer.send(textMsg);
            if (transactional) {
                session.commit();
            }
        }
        long nanosTaken_send = System.nanoTime() - nanosStart_send;
        double total = nanosTaken_send / 1_000_000d;
        log.info("############ Sent [" + numMessages + "] messages, took: " + total + " ms");
        return total;
    }

    private void receiveMessagesRunner(ConnectionFactory connectionFactory, int numMessages, CountDownLatch latch) {
        Connection connection = null;
        try {
            connection = connectionFactory.createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(TEST_QUEUE);
            MessageConsumer consumer = session.createConsumer(queue);
            int receivedMessages = 0;
            do {
                Message msg = consumer.receive(5000);
                if (msg == null) {
                    return;
                }
                if (msg instanceof TextMessage) {
                    TextMessage textMsg = (TextMessage) msg;
                }
            } while (++receivedMessages != numMessages);
            log.info("Received all expected [" + numMessages + "], exiting.");
        }
        catch (JMSException e) {
            log.warn("Threw out.", e);
        }
        if (connection != null) {
            try {
                connection.close();
            }
            catch (JMSException e) {
                log.warn("When closing.", e);
            }
        }
        latch.countDown();
    }
}
