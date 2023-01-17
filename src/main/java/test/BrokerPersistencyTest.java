package test;

import org.apache.activemq.store.kahadb.disk.journal.Journal.JournalDiskSyncStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.Util.BrokerAndConnectionFactory;

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
 * Tests sending of few thousand messages, with different parameters wrt. backing store, transactional,
 * and non-persistent - to see how these interact.
 *
 * @author Endre Stølsvik 2022-01-26 01:07 - http://stolsvik.com/, endre@stolsvik.com
 */
public class BrokerPersistencyTest {

    private static final Logger log = LoggerFactory.getLogger(BrokerPersistencyTest.class);

    private static final String TEST_QUEUE = "TestQueue";

    public static void main(String... args) throws Exception {
        BrokerPersistencyTest brokerPersistencyTest = new BrokerPersistencyTest();
        brokerPersistencyTest.runTest();
    }

    public void runTest() throws Exception {
        // WARMUP
        runTest(1000, 50);

        log.info("Chilling");
        Thread.sleep(5000);

        // MEASURE
        runTest(2000, 100);
    }

    /**
     * MEASURE
     * <p>
     * Note: The non-Transactional & non-Persistent are so fast that we increase the number of messages.
     */
    private void runTest(int numMessages, int multiple) throws Exception {
        double nnn = runTest(numMessages * multiple, null, false, false);
        double nnp = runTest(numMessages, null, false, true);
        double ntn = runTest(numMessages, null, true, false);
        double ntp = runTest(numMessages, null, true, true);
        double ann = runTest(numMessages * multiple, JournalDiskSyncStrategy.ALWAYS, false, false);
        double anp = runTest(numMessages, JournalDiskSyncStrategy.ALWAYS, false, true);
        double atn = runTest(numMessages, JournalDiskSyncStrategy.ALWAYS, true, false);
        double atp = runTest(numMessages, JournalDiskSyncStrategy.ALWAYS, true, true);
        double pnn = runTest(numMessages * multiple, JournalDiskSyncStrategy.PERIODIC, false, false);
        double pnp = runTest(numMessages, JournalDiskSyncStrategy.PERIODIC, false, true);
        double ptn = runTest(numMessages, JournalDiskSyncStrategy.PERIODIC, true, false);
        double ptp = runTest(numMessages, JournalDiskSyncStrategy.PERIODIC, true, true);
        nnn = nnn / multiple;
        ann = ann / multiple;
        pnn = pnn / multiple;
        log.info("Each test is time for sending and in case of 'Transactional', committing, [" + numMessages + "] messages");
        log.info(String.format("no store, non-Transactional, non-Persistent: . [%8.3f] ms", nnn));
        log.info(String.format("no store, non-Transactional, Persistent: ..... [%8.3f] ms", nnp));
        log.info(String.format("no store, Transactional, non-Persistent: ..... [%8.3f] ms", ntn));
        log.info(String.format("no store, Transactional, Persistent: ......... [%8.3f] ms", ntp));
        log.info("---------------------------------------------------------------------------------");
        log.info(String.format("ALWAYS, non-Transactional, non-Persistent: ... [%8.3f] ms   <-- This is great, avoiding hit even though ALWAYS sync!", ann));
        log.info(String.format("ALWAYS, non-Transactional, Persistent: ....... [%8.3f] ms", anp));
        log.info(String.format("ALWAYS, Transactional, non-Persistent: ....... [%8.3f] ms   <-- Why is this not fast, since message is not stored?", atn));
        log.info(String.format("ALWAYS, Transactional, Persistent: ........... [%8.3f] ms", atp));
        log.info("---------------------------------------------------------------------------------");
        log.info(String.format("PERIODIC, non-Transactional, non-Persistent: . [%8.3f] ms", pnn));
        log.info(String.format("PERIODIC, non-Transactional, Persistent: ..... [%8.3f] ms", pnp));
        log.info(String.format("PERIODIC, Transactional, non-Persistent: ..... [%8.3f] ms", ptn));
        log.info(String.format("PERIODIC, Transactional, Persistent: ......... [%8.3f] ms", ptp));
    }

    private double runTest(int numMessages, JournalDiskSyncStrategy syncStrategy, boolean transactional,
            boolean persistent) throws Exception {
        BrokerAndConnectionFactory broker = Util.createBroker(syncStrategy);
        ConnectionFactory connectionFactory = broker.getConnectionFactory();


        // :: CREATE CONSUME MESSAGES THREAD
        CountDownLatch latch = new CountDownLatch(1);
        Thread receiveThread = new Thread(() -> {
            this.receiveMessagesRunner(connectionFactory, numMessages, latch);
        }, "TestReceiver:" + syncStrategy + ";trans=" + transactional + ";persist=" + persistent);
        receiveThread.start();

        // :: RUN TEST: Send a bunch of messages
        double millisTaken = sendBunchOfMessages(connectionFactory, numMessages, transactional, persistent);
        log.info("Done sending");

        // Wait for all message to be received
        boolean latched = latch.await(10, TimeUnit.SECONDS);
        if (!latched) {
            throw new AssertionError("Didn't received expected number [" + numMessages + "] of messages");
        }
        log.info("Done receiving.");

        broker.closeBroker();
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
        // NOTICE: It makes no difference whether you set the DeliveryMode on the producer, or when sending msg.
        // Either you set it here on the producer, or below on the send.
        producer.setDeliveryMode(persistent ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT);
        log.info("============ Starting sending of [" + numMessages + "] messages");
        long nanosStart_send = System.nanoTime();
        for (int i = 0; i < numMessages; i++) {
            TextMessage textMsg = session.createTextMessage();
            textMsg.setText("Text:" + i);

            // NOTICE: It makes no difference whether you set the DeliveryMode on the producer, or when sending msg.
            // Either you set it here on the send, or above on the producer.
            // producer.send(textMsg, persistent ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT, 4, 0);
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
