# Interesting performance wrt. NON_PERSISTENT messaging

When running ActiveMQ with default config, it uses KahaDB as backend with ```JournalDiskSyncStrategy.ALWAYS``` which
flushes to disk after every operation. This is to live up to the JMS promises of persistent messaging where operations
should actually have been stored to disk when e.g. producer.send() or session.commit() returns.

This constant flushing gives a very heavy performance impact, and one should really consider the pros and cons of using
PERIODIC instead.

However, I was a bit surprised by an observation: If you  run the JMS Session in Transactional mode, but also do 
```producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT)```, one might believe that there wouldn't be a need to flush
the disk, as one has specifically told the system that "this doesn't really matter". However, the performance impact
from "Transactional, non-Persistent" is just as heavy as with non-Transactional+Persistent and Transactional+Persistent.

With non-Transactional + non-Persistent, even with ```JournalDiskSyncStrategy.ALWAYS```, the speed is blazing.

One might argue, well don't use Transactional then. But Transactional also brings another feature, whereby you may
receive a message, and send a message, and have these two operations inside a transactional boundary. This aspect
of Transactional is valuable even if though the messages are non-Persistent. And the broker evidently handles this
logic already even without persistence, since if you say ```BrokerService.setPersistent(false)``` and thus turn off the
entire storage of the broker, it still handles this aspect of Transactional messaging.

It would have been valuable to be able to "selectively" turn off this pretty massive hit of flushing to disk if the
messages inside the Transaction was non-Persistent. 

Also: Only the ```jmsProducer.setDeliveryMode(..)``` have any effect on the timing: If employing the 
```jmsMessage.setJMSDeliveryMode(..)``` method to get semantically exactly the same messages sent with same DeliveryMode
(that is, PERSISTENT vs. NON_PERSISTENT), you lose out on those blazing fast "non-Transactional, non-Persistent"
timings.

Note: The Java class in this repo uses an in-vm broker, with the "vm://" connection. However, this situation was first
observed with a remote broker, so the effects of interest are the same over TCP or over VM.

When setting DeliveryMode (PERSISTENT vs. NON_PERSISTENT) on both jmsProducer and jmsMessage:
```
 Each test is time for sending and in case of 'Transactional', committing, [1000] messages
 
 no store, non-Transactional, non-Persistent:   [4.26321216] ms 
 no store, non-Transactional, Persistent:   [26.784713] ms 
 no store, Transactional, non-Persistent:   [32.404009] ms 
 no store, Transactional, Persistent:   [46.314925] ms 
 ALWAYS, non-Transactional, non-Persistent:   [3.9833712400000003] ms 
 ALWAYS, non-Transactional, Persistent:   [4235.887625] ms 
 ALWAYS, Transactional, non-Persistent:   [4040.549101] ms    <-- This one is unfortunate!
 ALWAYS, Transactional, Persistent:   [3902.525469] ms 
 PERIODIC, non-Transactional, non-Persistent:   [3.85038125] ms 
 PERIODIC, non-Transactional, Persistent:   [78.012904] ms 
 PERIODIC, Transactional, non-Persistent:   [39.030052] ms 
 PERIODIC, Transactional, Persistent:   [94.339609] ms 

```

When setting DeliveryMode (PERSISTENT vs. NON_PERSISTENT) *only on jmsMessage*:
```
Each test is time for sending and in case of 'Transactional', committing, [1000] messages
no store, non-Transactional, non-Persistent:   [32.237903] ms   <- This is no longer superfast
no store, non-Transactional, Persistent:   [27.756596] ms
no store, Transactional, non-Persistent:   [37.082023] ms
no store, Transactional, Persistent:   [35.110782] ms
ALWAYS, non-Transactional, non-Persistent:   [4108.457421] ms   <- ... and neither this
ALWAYS, non-Transactional, Persistent:   [4102.393857] ms
ALWAYS, Transactional, non-Persistent:   [3954.603762] ms
ALWAYS, Transactional, Persistent:   [3978.435732] ms
PERIODIC, non-Transactional, non-Persistent:   [58.224914] ms   <- .. nor this
PERIODIC, non-Transactional, Persistent:   [81.982655] ms
PERIODIC, Transactional, non-Persistent:   [67.837032] ms
PERIODIC, Transactional, Persistent:   [58.78441] ms
```
