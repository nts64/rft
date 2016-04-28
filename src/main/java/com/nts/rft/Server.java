package com.nts.rft;

import com.nts.rft.sbe.*;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.TimerWheel;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.nts.rft.Settings.*;

class Server  {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private static Transaction[] EMPTY = new Transaction[0];
    private final Worker worker = new Worker();

    private Messaging messaging;
    private Sender sender;
    private Receiver receiver;

    private long currentTerm;
    private int votedFor;
    private int votesReceived;
    private State currentState = State.follower;
    private TimerWheel.Timer electionTimer;
    private Runnable electionTimerTask = this::processElectionTimeout;
    private TimerWheel.Timer heartbeatTimer;
    private Runnable heartbeatTimerTask = this::processHeartbeatTimeout;
    private Settings settings;

    Server(Settings settings) {
        this.settings = settings;
    }

    void start() {
        messaging = new Messaging();
        receiver = messaging.receiver();
        sender = messaging.sender();
        worker.start();
        heartbeatTimer = settings.timerWheel.newBlankTimer();
        electionTimer = settings.timerWheel.newBlankTimer();
        resetElectionTimeout();
    }

    void shutdown() {
        worker.shutdown();
        messaging.close();
    }

    //Server message processor
    private void processRequestVote(RequestVoteDecoder decoder) {
        logEntry("processRequestVote");
        checkCurrentTerm(decoder.term());
        switch (currentState) {
            case follower:
                if (decoder.term() < currentTerm) {
                    sender.sendRequestVoteResponse(currentTerm, decoder.candidateId(), false);
                } else if (votedFor == 0) {
                    //TODO candidate log is at least as up-to-date as receiver's log
                    votedFor = decoder.candidateId();
                    sender.sendRequestVoteResponse(currentTerm, decoder.candidateId(), true);
                    resetElectionTimeout();
                }  else {
                    sender.sendRequestVoteResponse(currentTerm, decoder.candidateId(), false);
                }
                break;
            case candidate:
                sender.sendRequestVoteResponse(currentTerm, decoder.candidateId(), false);
                break;
            case leader:
                sender.sendRequestVoteResponse(currentTerm, decoder.candidateId(), false);
                break;
        }
        logExit();
    }

    //Server message processor
    private void processRequestVoteResponse(RequestVoteResponseDecoder decoder) {
        logEntry("processRequestVoteResponse");
        checkCurrentTerm(decoder.term());
        switch (currentState) {
            case follower:
                trace("Follower receiving request vote response");
                break;
            case candidate:
                if (decoder.voteGranted() == BooleanType.True) {
                    votesReceived++;
                    if (votesReceived > settings.clusterSize / 2) {
                        changeState(State.leader);
                        resetHeartbeatTimeout();
                        sendHeartbeat();
                        electionTimer.cancel();
                    }
                }
                break;
            case leader:
                trace("Leader receiving request vote response");
                break;
        }
        logExit();
    }

    //Server message processor
    private void processAppendEntries(AppendEntriesDecoder decoder) {
        logEntry("processAppendEntries");
        checkCurrentTerm(decoder.term());
        if (decoder.term() < currentTerm) {
            sender.sendAppendEntriesResponse(decoder.leaderId(), currentTerm, false);
        }

        switch (currentState){
            case leader:
                trace("AppendEntries received by leader");
                break;
            case candidate:
                trace("AppendEntries received by candidate");
                break;
            case follower:
                trace("AppendEntries received by follower");
                AppendEntriesDecoder.EntriesDecoder entries = decoder.entries();
                int count = entries.count();

                Transaction[] data = new Transaction[count];
                int index = 0;
                for (AppendEntriesDecoder.EntriesDecoder entriesDecoder : entries) {
                    data[index] = new Transaction();
                    data[index].creditAccount = entriesDecoder.creditAccount();
                    data[index].debitAccount = entriesDecoder.debitAccount();
                    data[index].amount = entriesDecoder.amount();
                    index++;
                }

                resetElectionTimeout();
                sender.sendAppendEntriesResponse(decoder.leaderId(), currentTerm, true);
                break;
        }
        logExit();
    }

    //Server message processor
    private void processAppendEntriesResponse(AppendEntriesResponseDecoder decoder) {
        logEntry("processAppendEntriesResponse");
        checkCurrentTerm(decoder.term());
        logExit();
    }

    //Timeout processor
    private void processElectionTimeout() {
        logEntry("processElectionTimeout");
        switch (currentState) {
            case follower:
                //start election
                currentTerm++;
                changeState(State.candidate);
                sender.sendRequestVote(currentTerm, settings.id, 0, 0);
                votedFor = settings.id;
                votesReceived = 1;
                resetElectionTimeout();
                break;
            case candidate: {
                //resend the vote request
                sender.sendRequestVote(currentTerm, settings.id, 0, 0);
                resetElectionTimeout();
                break;
            }
            case leader: {
                trace("leader received election timeout");
                break;
            }
        }
        logExit();
    }

    //Timeout processor
    private void processHeartbeatTimeout() {
        logEntry("processHeartbeatTimeout");
        assert currentState == State.leader;
        sendHeartbeat();
        logExit();
    }

    //Client message processor
    private void processClientConnect(ClientConnectDecoder decoder, int sessionId) {
        //TODO
        String clientChannel = decoder.clientChannel();
        if (currentState == State.leader) {

        } else {

        }
    }

    //Client message processor
    private void processBalanceRequest(BalanceRequestDecoder decoder, int sessionId) {
        //TODO
    }

    //Client message processor
    private void processTransactionRequest(TransactionRequestDecoder decoder, int sessionId) {
        //TODO
    }

    private void sendHeartbeat() {
        sendAppendEntriesRequest(EMPTY);
    }

    private void sendAppendEntriesRequest(Transaction[] entries) {
        sender.sendAppendEntries(currentTerm, settings.id, 0, 0, entries, 0);
        resetHeartbeatTimeout();
    }

    private void resetHeartbeatTimeout() {
        heartbeatTimer.cancel();
        settings.timerWheel.rescheduleTimeout(settings.heartbeatTimeout, TimeUnit.MILLISECONDS, heartbeatTimer, heartbeatTimerTask);
    }

    private void resetElectionTimeout() {
        electionTimer.cancel();
        settings.timerWheel.rescheduleTimeout(settings.electionTimeout, TimeUnit.MILLISECONDS, electionTimer, electionTimerTask);
    }

    private void checkCurrentTerm(long term) {
        if (term > currentTerm) {
            trace("Received message with term: {}, larger than this one: {}", term, currentTerm);
            currentTerm = term;
            if (currentState != State.follower) {
                changeState(State.follower);
            }
            heartbeatTimer.cancel();
            votedFor = 0;
        }
    }

    private void changeState(State next) {
        trace("Changing state from {} to {}", currentState, next);
        assert currentState.canTransitionTo(next);
        currentState = next;
    }

    private class Worker {
        final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Worker thread"));
        final AtomicBoolean running = new AtomicBoolean(true);

        void start() {
            executor.execute(() -> {
                while (running.get()) {
                    settings.idleStrategy.idle(doWork());
                }
            });
        }

        int doWork() {
            int result = receiver.checkForMessages();
            if (settings.timerWheel.computeDelayInMs() < 0) {
                result += settings.timerWheel.expireTimers();
            }
            return result;
        }

        void shutdown() {
            running.set(false);
            executor.shutdown();
        }
    }

    private class Receiver {
        final List<Subscription> subscriptions;
        final Subscription clientSubscription;
        final FragmentHandler serverMessageHandler;
        final FragmentHandler clientMessageHandler;

        final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
        final RequestVoteDecoder requestVote = new RequestVoteDecoder();
        final RequestVoteResponseDecoder requestVoteResponse = new RequestVoteResponseDecoder();
        final AppendEntriesDecoder appendEntries = new AppendEntriesDecoder();
        final AppendEntriesResponseDecoder appendEntriesResponse = new AppendEntriesResponseDecoder();

        final ClientConnectDecoder clientConnect = new ClientConnectDecoder();
        final BalanceRequestDecoder balanceRequest = new BalanceRequestDecoder();
        final TransactionRequestDecoder transactionRequest = new TransactionRequestDecoder();

        Receiver(Aeron aeron) {
            subscriptions = new ArrayList<>(settings.clusterSize - 1);
            for (int i = 1; i <= settings.clusterSize; i++) {
                if (i == settings.id) {
                    continue;
                }
                int stream_id = i * 10 + settings.id;
                String channel = settings.channelPrefix + stream_id;
                trace("Subscription to {}", channel);
                Subscription subscription = aeron.addSubscription(channel, stream_id);
                subscriptions.add(subscription);
            }
            serverMessageHandler = (buffer, offset, length, header) -> {
                messageHeader.wrap(buffer, offset);
                switch (messageHeader.templateId()) {
                    case RequestVoteDecoder.TEMPLATE_ID:
                        requestVote.wrap(buffer, offset + messageHeader.encodedLength(), messageHeader.blockLength(), 0);
                        processRequestVote(requestVote);
                        break;
                    case RequestVoteResponseDecoder.TEMPLATE_ID:
                        requestVoteResponse.wrap(buffer, offset + messageHeader.encodedLength(), messageHeader.blockLength(), 0);
                        processRequestVoteResponse(requestVoteResponse);
                        break;
                    case AppendEntriesDecoder.TEMPLATE_ID:
                        appendEntries.wrap(buffer, offset + messageHeader.encodedLength(), messageHeader.blockLength(), 0);
                        processAppendEntries(appendEntries);
                        break;
                    case AppendEntriesResponseDecoder.TEMPLATE_ID:
                        appendEntriesResponse.wrap(buffer, offset + messageHeader.encodedLength(), messageHeader.blockLength(), 0);
                        processAppendEntriesResponse(appendEntriesResponse);
                        break;
                    default:
                        warn("Unsupported message with template id: {}", messageHeader.templateId());
                }
            };

            clientSubscription = aeron.addSubscription(settings.clientChannelPrefix + settings.id, settings.id);
            clientMessageHandler = (buffer, offset, length, header) -> {
                messageHeader.wrap(buffer, offset);
                switch (messageHeader.templateId()) {
                    case ClientConnectDecoder.TEMPLATE_ID:
                        clientConnect.wrap(buffer, offset + messageHeader.encodedLength(), messageHeader.blockLength(), 0);
                        processClientConnect(clientConnect, header.sessionId());
                        break;
                    case BalanceRequestDecoder.TEMPLATE_ID:
                        balanceRequest.wrap(buffer, offset + messageHeader.encodedLength(), messageHeader.blockLength(), 0);
                        processBalanceRequest(balanceRequest, header.sessionId());
                        break;
                    case TransactionRequestDecoder.TEMPLATE_ID:
                        transactionRequest.wrap(buffer, offset + messageHeader.encodedLength(), messageHeader.blockLength(), 0);
                        processTransactionRequest(transactionRequest, header.sessionId());
                        break;
                    default:
                        warn("Unsupported client message with template id: {}", messageHeader.templateId());
                }
            };
        }

        int checkForMessages() {
            int result = 0;
            for (Subscription subscription : subscriptions) {
                result += subscription.poll(serverMessageHandler, 256);
            }
            return result;
        }

    }

    private class Messaging {
        final MediaDriver driver;
        final Aeron aeron;

        Messaging() {
            driver = MediaDriver.launchEmbedded();
            Aeron.Context context = new Aeron.Context();
            context.aeronDirectoryName(driver.aeronDirectoryName());
            aeron = Aeron.connect(context);
        }

        Receiver receiver() {
            return new Receiver(aeron);
        }

        Sender sender() {
            return new Sender(aeron);
        }

        ClientSender clientSender(String channel) {
            return new ClientSender(aeron, channel);
        }

        void close() {
            aeron.close();
            driver.close();
        }
    }

    private class Sender {
        final Publication[] publications;
        final UnsafeBuffer buffer;

        final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
        final RequestVoteEncoder requestVoteEncoder = new RequestVoteEncoder();
        final RequestVoteResponseEncoder requestVoteResponseEncoder = new RequestVoteResponseEncoder();
        final AppendEntriesEncoder appendEntriesEncoder = new AppendEntriesEncoder();
        final AppendEntriesResponseEncoder appendEntriesResponseEncoder = new AppendEntriesResponseEncoder();

        Sender(Aeron aeron) {
            publications = new Publication[settings.clusterSize];
            for (int i = 1; i <= settings.clusterSize; i++) {
                if (i == settings.id) {
                    continue;
                }
                int streamId = settings.id * 10 + i;
                String channel = settings.channelPrefix + streamId;
                trace("Publication to {}", channel);
                publications[i - 1] = aeron.addPublication(channel, streamId);
            }
            buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        }

        void sendRequestVote(long term, int candidateId, long lastLogIndex, long lastLogTerm) {
            messageHeaderEncoder.wrap(buffer, 0)
                                .blockLength(RequestVoteEncoder.BLOCK_LENGTH)
                                .templateId(RequestVoteEncoder.TEMPLATE_ID)
                                .schemaId(RequestVoteEncoder.SCHEMA_ID)
                                .version(RequestVoteEncoder.SCHEMA_VERSION);
            requestVoteEncoder.wrap(buffer, messageHeaderEncoder.encodedLength())
                              .term(term)
                              .candidateId((short) candidateId)
                              .lastLogIndex(lastLogIndex)
                              .lastLogTerm(lastLogTerm);

            sendAll(messageHeaderEncoder.encodedLength() + requestVoteEncoder.encodedLength());
        }

        void sendRequestVoteResponse(long term, int candidateId, boolean voteGranted) {
            messageHeaderEncoder.wrap(buffer, 0)
                                .blockLength(RequestVoteResponseEncoder.BLOCK_LENGTH)
                                .templateId(RequestVoteResponseEncoder.TEMPLATE_ID)
                                .schemaId(RequestVoteResponseEncoder.SCHEMA_ID)
                                .version(RequestVoteResponseEncoder.SCHEMA_VERSION);
            requestVoteResponseEncoder.wrap(buffer, messageHeaderEncoder.encodedLength())
                                      .term(term)
                                      .voteGranted(voteGranted ? BooleanType.True : BooleanType.False);
            int length = messageHeaderEncoder.encodedLength() + requestVoteResponseEncoder.encodedLength();
            send(publicationFor(candidateId), length);
        }

        void sendAppendEntries(long term, int leaderId, long previousLogIndex, long previousLogTerm, Transaction[] entries, long leaderCommit) {
            messageHeaderEncoder.wrap(buffer, 0)
                                .blockLength(AppendEntriesEncoder.BLOCK_LENGTH)
                                .templateId(AppendEntriesEncoder.TEMPLATE_ID)
                                .schemaId(AppendEntriesEncoder.SCHEMA_ID)
                                .version(AppendEntriesEncoder.SCHEMA_VERSION);
            appendEntriesEncoder.wrap(buffer, messageHeaderEncoder.encodedLength())
                                .term(term)
                                .leaderId((short) leaderId)
                                .prevLogIndex(previousLogIndex)
                                .prevLogTerm(previousLogTerm)
                                .leaderCommit(leaderCommit);
            AppendEntriesEncoder.EntriesEncoder entriesEncoder = appendEntriesEncoder.entriesCount(entries.length);
            for (Transaction entry : entries) {
                entriesEncoder.amount(entry.amount).creditAccount(entry.creditAccount).debitAccount(entry.debitAccount);
            }

            sendAll(messageHeaderEncoder.encodedLength() + appendEntriesEncoder.encodedLength());
        }

        void sendAppendEntriesResponse(int leaderId, long term, boolean success) {
            messageHeaderEncoder.wrap(buffer, 0)
                                .blockLength(AppendEntriesResponseEncoder.BLOCK_LENGTH)
                                .templateId(AppendEntriesResponseEncoder.TEMPLATE_ID)
                                .schemaId(AppendEntriesResponseEncoder.SCHEMA_ID)
                                .version(AppendEntriesResponseEncoder.SCHEMA_VERSION);
            appendEntriesResponseEncoder.wrap(buffer, messageHeaderEncoder.encodedLength())
                                        .term(term)
                                        .success(success ? BooleanType.True : BooleanType.False);
            send(publicationFor(leaderId), messageHeaderEncoder.encodedLength() + appendEntriesResponseEncoder.encodedLength());
        }

        void sendAll(int length) {
            for (Publication publication : publications) {
                if (publication == null) {
                    continue;
                }
                send(publication, length);
            }
        }

        void send(Publication publication, int length) {
            long result = publication.offer(buffer, 0, length);
            if (result < 0) {
                warn("Failed publishing to {}, {}, error code {}", publication.channel(), publication.streamId(), result);
            }
        }

        Publication publicationFor(int id) {
            return publications[id - 1];
        }

    }

    private class ClientSender {
        final Publication clientPublication;
        final UnsafeBuffer buffer;
        final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
        final ClientResponseEncoder clientResponseEncoder = new ClientResponseEncoder();
        final BalanceResponseEncoder balanceResponseEncoder = new BalanceResponseEncoder();
        final TransactionResponseEncoder transactionResponseEncoder = new TransactionResponseEncoder();

        ClientSender(Aeron aeron, String clientChannel) {
            clientPublication = aeron.addPublication(clientChannel, settings.clientStreamId);
            buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        }

        void sendClientResponse(int leaderId) {
            messageHeaderEncoder.wrap(buffer, 0)
                                .blockLength(ClientResponseEncoder.BLOCK_LENGTH)
                                .templateId(ClientResponseEncoder.TEMPLATE_ID)
                                .schemaId(ClientResponseEncoder.SCHEMA_ID)
                                .version(ClientResponseEncoder.SCHEMA_VERSION);
        }
    }

    private class Transaction {
        long creditAccount, debitAccount, amount;
    }

    private long methodEntry;
    private String entryPoint;

    private void trace(String message) {
        if (LOG_TRACE_ENABLED) {
            logger.info(message);
        }
    }

    private void trace(String message, State param1, State param2) {
        if (LOG_TRACE_ENABLED) {
            logger.info(message, param1, param2);
        }
    }

    private void trace(String message, long param1, long param2) {
        if (LOG_TRACE_ENABLED) {
            logger.info(message, param1, param2);
        }
    }

    private void trace(String message, String param) {
        if (LOG_TRACE_ENABLED) {
            logger.info(message, param);
        }
    }

    private void logEntry(Object entryPoint) {
        if (LOG_TIME_ENABLED) {
            methodEntry = System.nanoTime();
            this.entryPoint = entryPoint.toString();
        }
    }

    private void logExit(){
        if (LOG_TIME_ENABLED){
            logger.info("{} {}", entryPoint, TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - methodEntry));
        }
    }

    private void warn(String message, Object... params) {
        if (LOG_WARN_ENABLED) {
            logger.warn(message, params);
        }
    }
}
