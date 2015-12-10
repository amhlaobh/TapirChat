package tc;

import static tc.Message.MessageType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** 
 * "Secret" input strings:
 * "WHO": asks server who is online
 * "LOG <LEVEL>": changes log level. Values are: SEVERE,WARNING,INFO,FINE,FINER,FINEST
 * @author ok
 *
 */

public class TextUI {

    // static fields for this class only
    private static final int POLLING_INTERVAL = 1000;
    // how many heartbeats not returned from server before giving up?
    private static final int HEARTBEAT_GRACE_PERIOD = 1;
    // how many entries max. for LRU caches
    private static final int MAX_ENTRIES_LRU = 200;
    private static final String ENC_UTF8 = "UTF-8";

    static final String SECRET_LOG = "LOG";
    static final String SECRET_WHO = "WHO";
    static final String SECRET_STATS = "STATS";

    // package private fields for this class and subclasses
    SendThread sendThread;
    ReceiveThread receiveThread;
    UserInputThread userInputThread; 
    ObjectCrypter objCrypt = null;
    Tray tray;
    String userName;
    // BlockingQueue is thread-safe
    ArrayBlockingQueue<Message> messageQueue = new ArrayBlockingQueue<Message>(200);
    long bytesSent = 0l;
    long bytesReceived = 0l;
    long messagesSent = 0l;
    long messagesReceived = 0l;
    

    // private fields for this class only
    private Timer heartbeatTimer;
    private Scanner scanner;
    private BufferedWriter srvOutWriter;
    private BufferedReader srvInReader;
    private boolean showPopups = true;
    private String host;
    private int port;
    private int localPort = 0;
    private Socket remoteSocket;
    private Map<String, Message> sentMessages = Collections.synchronizedMap(new HashMap<String, Message>());
    private byte[] initializationVector = new byte[]{0x05, 0x06, 0x07, 0x08, 0x09, 0x04, 0x03, 0x01};
    
    // receivedMessageIds only used in receivethread, therefore thread-safe
    // only key is relevant (just using the LinkedHashMap as a ready-made LRU list
    private Map<String,Object> receivedMessageIds = new LinkedHashMap<String,Object>( 10, 0.75f, true ){
        @SuppressWarnings("unchecked")
        protected boolean removeEldestEntry(java.util.Map.Entry entry) {
            return size() > MAX_ENTRIES_LRU;
        }
     };
    
    // all access must be synchronized 
    // only key is relevant (just using the LinkedHashMap as a ready-made LRU list
    private Map<String,Object> sentMessageIds = new LinkedHashMap<String,Object>( 10, 0.75f, true ){
        @SuppressWarnings("unchecked")
        protected boolean removeEldestEntry(java.util.Map.Entry entry) {
            return size() > MAX_ENTRIES_LRU;
        }
     };
    
    private AtomicInteger heartbeatsSent = new AtomicInteger();
    
    private boolean useEncryption;
    private Cipher decCipher;
    private Cipher encCipher;
    private byte[] defaultKey = new byte[]{-25,101,-45,12,93,-38,-56,-7,-99,109,-83,78,-90,90,96,106};
    static boolean usePayloadEncryption = true;
    
    AtomicBoolean serverNotResponding = new AtomicBoolean(true);
    private AtomicLong lastMessageTime = new AtomicLong(-1);
    
    private int heartbeatInterval;
    
    public TextUI (Options options) throws IOException {
        
        port = options.optPort;
        localPort = options.optLocalPort;
        setPopups (options.optPopups);
        if (options.optUseEncryption) {
            setEncryption(options.optSecretKey);
        }
        if (options.optUsePayloadEncryption){
            objCrypt = new ObjectCrypter(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}/*options.optSecretKey*/, initializationVector);
        }
        usePayloadEncryption =  options.optUsePayloadEncryption;
        Logger.log(Level.CONFIG, "Payload encryption: "+usePayloadEncryption);
        
        if (options.optArchiveMinutes != null){
            lastMessageTime.set(
                    System.currentTimeMillis() - options.optArchiveMinutes * 60 * 1000L);
        } else {
            lastMessageTime.set(System.currentTimeMillis());
        }

        this.userName = options.optUser;
        this.host = options.optHost;
        Logger.log(Level.INFO, "Starting textUI on host:port " + host + ":" + port);
        Logger.log(Level.INFO, "  -- user "+userName);

        heartbeatInterval = options.optHeartbeat;

        if (options.optPopups) tray = new Tray(this);
        
        scanner = new Scanner(System.in);
        
    }
    
    void sendMessage (Message msg) throws IOException {
        if (srvOutWriter == null) return;

        String ms = msg.toStructuredString();
        srvOutWriter.write(ms);
        srvOutWriter.write(Options.EOL);
        srvOutWriter.flush();

        bytesSent += ms.length();
        messagesSent++;
        Logger.log(Level.FINEST, String.format("Sent %10d bytes, total %s in %5d messages", ms.length(), 
            formatBytes(bytesSent), messagesSent));
        
        if (MessageType.USERMSG == msg.messageType && tray != null) tray.resetAlert();
    }
        
    private Message receiveMessage () throws IOException, Message.MessageException {
        if (srvInReader == null) return null;
        
        StringBuilder msgStr = new StringBuilder();
        int c;
        while ((c = srvInReader.read()) != -1 && c != Options.EOL){
            msgStr.append((char)c);
        }
        
        Message msg = new Message(msgStr.toString());
        if (msg != null){
            String ms = msg.toStructuredString();
            Logger.log (Level.FINEST, "Received message: " + ms);
            bytesReceived += ms.length();
            messagesReceived++;
            Logger.log(Level.FINEST, String.format("Received %10d bytes, total %s in %5d messages", ms.length(), 
                formatBytes(bytesReceived), messagesReceived));
        }
        
        return msg;
    }
    
    public void shutdownUI (){
        Logger.log(Level.INFO, "Shutting down " + userName);
        if (heartbeatTimer != null) heartbeatTimer.cancel(); 
        if (userInputThread != null) userInputThread.shutdown();
        if (sendThread != null) sendThread.shutdown();
        if (receiveThread != null) receiveThread.shutdown();
        try {
            Logger.log(Level.FINER, "Sending shutdown message");
            sendMessage(new Message(userName, MessageType.SHUTDOWN, "."));
        } catch (IOException ioe) {
            // can be triggered if server already unreachable
            Logger.log(Level.FINE, "While sending shutdown msg: ", ioe);
        }
        try {
            if (remoteSocket != null) remoteSocket.close();
        } catch (IOException ioe){
            Logger.log(Level.WARNING, "Couldn't close socket: ", ioe);
        }
        try {
            if (srvOutWriter != null) srvOutWriter.close();
        } catch (IOException ioe){
            Logger.log(Level.WARNING, "Couldn't close outputstream: ", ioe);
        }
        try {
            if (srvInReader != null) srvInReader.close();
        } catch (IOException ioe){
            Logger.log(Level.WARNING, "Couldn't close inputstream: ", ioe);
        }

        Logger.log(Level.WARNING, String.format("Sent total     %s in %5d messages", formatBytes(bytesSent), messagesSent));
        Logger.log(Level.WARNING, String.format("Received total %s in %5d messages; altogether %10s", 
            formatBytes(bytesReceived), messagesReceived, formatBytes(bytesReceived+bytesSent)));
            
    }
    
    void connect (long connectionLostTime) throws IOException {

        if (userInputThread == null){
            userInputThread = this.new UserInputThread();
            userInputThread.start();
        }
        
        setServerNotResponding(true);

        
        if (remoteSocket != null) remoteSocket.close();
        if (srvOutWriter != null) srvOutWriter.close();
        if (srvInReader != null) srvInReader.close();
        if (heartbeatTimer != null) heartbeatTimer.cancel();
        if (sendThread != null) sendThread.shutdown();
        if (receiveThread != null) receiveThread.shutdown();
        
        if (localPort != 0) {
            InetAddress localAddr = InetAddress.getLocalHost();
            Logger.log(Level.FINE, "  -- Local host:port " + localAddr + ":" + localPort);
            remoteSocket = new Socket(host, port, localAddr, localPort);
        } else {
            boolean socketOK = false;
            while (!socketOK){
                try {
                    Logger.log (Level.FINE, "Trying to open socket");
                    remoteSocket = new Socket(host, port);
                    
                    socketOK = true;

                    OutputStream srvOs = remoteSocket.getOutputStream();
                    InputStream srvIs = remoteSocket.getInputStream();
                    if (useEncryption) {
                        Logger.log(Level.FINE, "Initializing cipher streams");
                        srvInReader = new BufferedReader(new InputStreamReader(
                                new CipherInputStream(srvIs, decCipher), ENC_UTF8));
                        srvOutWriter = new BufferedWriter(new OutputStreamWriter(
                                new CipherOutputStream(srvOs, encCipher), ENC_UTF8));
                    } else {
                        srvOutWriter = new BufferedWriter(new OutputStreamWriter(srvOs, ENC_UTF8));
                        srvInReader = new BufferedReader(new InputStreamReader(srvIs, ENC_UTF8));
                    }
                } catch (NoRouteToHostException re){
                    socketOK = false;
                    Logger.log (Level.INFO, "Host " + host + " seems to be offline " + 
                                "(NoRouteToHostException). Sleeping "+(heartbeatInterval/1000)+" secs");
                    try {
                        Thread.sleep(heartbeatInterval);
                    } catch (InterruptedException ie){
                        // nothing to do here
                    }
                } catch (ConnectException ce){
                    socketOK = false;
                    Logger.log (Level.FINE, ce.getMessage());
                    Logger.log (Level.WARNING, "Host " + host + " connection failed " + 
                        "(ConnectException). Sleeping "+(heartbeatInterval/1000)+" secs");
                    try {
                        Thread.sleep(heartbeatInterval);
                    } catch (InterruptedException ie){
                        // nothing to do here
                    }
                } catch (IOException ioe){
                    socketOK = false;
                    Logger.log (Level.FINE, ioe.getMessage());
                    Logger.log (Level.WARNING, "Host " + host + " connection failed " + 
                        "(IOException). Sleeping "+(heartbeatInterval/1000)+" secs", ioe);
                    try {
                        Thread.sleep(heartbeatInterval);
                    } catch (InterruptedException ie){
                        // nothing to do here
                    }
                }
                
                if (!socketOK) continue;
                
                Logger.log (Level.INFO, "(Re)connected.");

                // don't want to wait forever on inputstream.read()
                remoteSocket.setSoTimeout(heartbeatInterval); 
                
                Message versionMsg = new Message(userName, MessageType.VERSION, Hub.VERSION);
                sendMessage(versionMsg);
                try {
                    Message ackMsg = receiveMessage();
                    if ( ! Message.verifyAck(versionMsg, ackMsg)) {
                        Logger.log(Level.SEVERE, "Expected version ACK, but got: " + ackMsg.toStructuredString());
                        System.exit(1);
                    }
                } catch (Message.MessageException iae) {
                    Logger.log (Level.SEVERE, "Expected version Ack message.", iae);
                    socketOK = false;
                    continue;
                } catch (SocketTimeoutException ste) {
                    Logger.log (Level.WARNING, "VERSION timeout.");
                    remoteSocket.close();
                    srvOutWriter.close();
                    srvInReader.close();
                    socketOK = false;
                    continue;
                } catch (SocketException se) {
                    Logger.log (Level.WARNING, "Socket reset on VERSION.");
                    remoteSocket.close();
                    srvOutWriter.close();
                    srvInReader.close();
                    socketOK = false;
                    continue;
                }
                
                Message connectMsg = new Message(userName, MessageType.CONNECT, userName);
                sendMessage(connectMsg);
                try {
                    Message ackMsg = receiveMessage();
                    if ( ! Message.verifyAck(connectMsg, ackMsg)) {
                        Logger.log(Level.WARNING, "Expected connect ACK, but got: " + ackMsg.toStructuredString());
                        continue;
                    }
                } catch (Message.MessageException iae) {
                    Logger.log (Level.WARNING, "Expected connect ack message.", iae);
                    socketOK = false;
                    continue;
                } catch (SocketTimeoutException se) {
                    Logger.log (Level.WARNING, "CONNECT timeout.");
                    socketOK = false;
                    continue;
                } catch (SocketException se) {
                    Logger.log (Level.WARNING, "Socket reset on CONNECT.");
                    socketOK = false;
                    continue;
                }

                Message heartbeatMsg = new Message(userName, MessageType.CONNECT, String.valueOf(heartbeatInterval));
                sendMessage(heartbeatMsg);
                try {
                    Message ackMsg = receiveMessage();
                    if ( ! Message.verifyAck(heartbeatMsg, ackMsg)) {
                        Logger.log(Level.WARNING, "Expected connect ACK, but got: " + ackMsg.toStructuredString());
                        continue;
                    }
                } catch (Message.MessageException iae) {
                    Logger.log (Level.WARNING, "Expected connect ack message.", iae);
                    continue;
                } catch (SocketTimeoutException se) {
                    Logger.log (Level.WARNING, "CONNECT timeout.");
                    socketOK = false;
                    continue;
                } catch (SocketException se) {
                    Logger.log (Level.WARNING, "Socket reset on CONNECT.");
                    socketOK = false;
                    continue;
                }
            }
        }

        
        Logger.log (Level.INFO, "Handshake complete.");
        setServerNotResponding(false);


        heartbeatTimer = new Timer (true);
        heartbeatTimer.schedule(this.new HeartbeatThread(), heartbeatInterval/2, heartbeatInterval);

        // now heartbeatTimer is taking care of timeouts
        remoteSocket.setSoTimeout(heartbeatInterval*2); 

        
        Message msg = new Message(userName, MessageType.ARCHIVE, String.valueOf(connectionLostTime));
        if (!messageQueue.offer(msg)){
            Logger.log (Level.WARNING, "Message queue full when adding user message after handshake, can't happen.");
        }

        receiveThread = new ReceiveThread();
        sendThread = new SendThread(userName, false);
        receiveThread.start();
        sendThread.start();
        
    }
    
    
    // ignore findbugs Method invokes System.exit(...), user pressed Ctrl-D
    String getUserInput(){
        System.out.print(">> ");
        String input = null;
        try {
            input = scanner.nextLine();
        } catch (NoSuchElementException nsee){
            Logger.log(Level.INFO, "Exiting.");
            shutdownUI();
            System.exit(0);
        }
        
        return input;
    }

    
    // log received text onto console
    void writeServerMessage(Message message){
        if (usePayloadEncryption){
            message.messageBody = objCrypt.decryptString(message.messageBody);
        }
        Logger.logStd(Level.SEVERE, message.userName+"# "+message.messageBody);
    }

    
    
    
    // --------------------------------------------SendThread-------

    class SendThread extends Thread {
        private volatile boolean running = true;
        
        public SendThread (String userName, boolean requestArchivedMessages) throws IOException {
            setName("SendThread");
            
            if (requestArchivedMessages){
                Message requestArchiveMsg = new Message (userName, MessageType.ARCHIVE, "");
                if (!messageQueue.offer(requestArchiveMsg)){
                    Logger.log (Level.WARNING, "Message queue full when adding archive request message.");
                }
            }
        }
        
        public SendThread (String userName) throws IOException {
            this(userName, true);
        }
        
        public void run(){
            while (running){
                Message msg = null;
                try {
//                    Logger.log(Level.FINEST, "Polling message queue");
                    msg = messageQueue.poll(POLLING_INTERVAL, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e){
                    Logger.log(Level.WARNING, "while polling", e);
                }
                if (msg == null) continue;

                Level lvl = Level.FINE;
                if (MessageType.HEARTBEAT == msg.messageType) lvl = Level.FINEST;
                Logger.log(lvl, "Sending message from queue: '"+msg.toStructuredString() + "'");
                
                synchronized (sentMessages) {
                    // receive thread might interfere
                    try {
                        sendMessage(msg);
                    } catch (IOException ioe){
                        running = false;
                        Logger.log(Level.FINE, "While sending queued message: " + ioe);
                        setServerNotResponding(true);
                    }
                    if (running) {
                        sentMessages.put(msg.messageId, msg);
                        if (MessageType.HEARTBEAT != msg.messageType){
                            // since in receive thread we are only interested in user/archive message ids 
                            sentMessageIds.put(msg.messageId, null);
                        }
                    }
                }
                
                if (!running && MessageType.USERMSG == msg.messageType) {
                    // re-add message on failure
                    if (!messageQueue.offer(msg)){
                        Logger.log (Level.FINE, "Message queue full when adding user message.");
                    }
                    Logger.log(Level.FINE, "Failure sending, re-add message: "+msg.messageType+"@"+
                            msg.messageBody);
                    continue;
                }
                
                // wait for ACK
                if (MessageType.USERMSG == msg.messageType || MessageType.HEARTBEAT == msg.messageType){
                    Logger.log(Level.FINEST, "Waiting for ACK");
                    boolean acked = false;
                    long now = System.currentTimeMillis();
                    while (System.currentTimeMillis() - now < heartbeatInterval * HEARTBEAT_GRACE_PERIOD){
                        Message ackMsg = sentMessages.get(msg.messageId);
                        if (ackMsg != null){
                            if (!ackMsg.acked) {
                                try {Thread.sleep(100);}
                                catch (InterruptedException e){
                                    //nothing to do here
                                };
                            } else {
                                acked = true;
                                break;
                            }
                        } else {
                            acked = true;
                            break;
                        }
                    
                    } // while
                    
                    if (!acked){
                        Logger.log(Level.WARNING, "Message not ACKed: " + msg.toStructuredString());
                        if ( MessageType.HEARTBEAT != msg.messageType){
                            messageQueue.add(msg);
                            Logger.log(Level.FINE, "Failure sending, re-add message: "+msg.messageType+"@"+
                                    msg.messageId);
                        }
                    } else {
                        // TODO: something wrong on connection lost
                        Logger.log(Level.FINER, "Message ACKed: " + msg.toStructuredString());
                    }
                }
            }
            
            Logger.log (Level.FINE, "SendThread ends: " + this.toString());
            shutdown();
        }
        
        public void shutdown(){
            running = false;
        }

        public boolean isRunning() {
            return running;
        }
    }

    // -------------------------ReceiveThread
    
    class ReceiveThread extends Thread {
        private volatile boolean running = true;
        
        public ReceiveThread (){
            setName("RcveThread");
        }
        
        public void run(){
            while (running){
                try {
                    Message msg = null;
                    try {
                        msg = receiveMessage();
                    } catch (Message.MessageException me) {
                        Logger.log(Level.WARNING, "Expected message, was null.");
                        Logger.log(Level.SEVERE, "Verbindung beendet");
                        shutdown();
                        continue;
                    }
                    
                    if (!running) continue;

                    if (msg == null) {
                        Logger.log (Level.FINE, "Message null");
                        continue;
                    }
                    
                    heartbeatsSent.set(0);
                    setServerNotResponding(false);

                    if (receivedMessageIds.containsKey(msg.messageId)){
                        // probably an archived message that we already got
                        // as a USERMSG
                        Logger.log (Level.FINEST, "Skipping " + msg.toStructuredString());
                        continue;
                    }
                    
                    synchronized (sentMessages){
                        // it is important only to check this after msg has been added to sentMessages
                        Message m = sentMessages.get(msg.messageId);
                        if ((m != null) && (!m.acked)){
                            if (Message.verifyAck(m, msg)) {
                                m.acked = true;
                                setServerNotResponding(false);
                                Logger.log(Level.FINEST, "  ACK, Removing from sentMessages: "+msg.toStructuredString());
                                sentMessages.remove(msg.messageId);
                            } else {
                                // most likely race condition on message id (other client has created same message id)
                                Logger.log (Level.WARNING, "Could not verify ACK message: " + msg.toStructuredString());
                            }
                        } else {
                            if (MessageType.HEARTBEAT == msg.messageType)
                                    Logger.log(Level.WARNING, "Received unsolicited heartbeat: " + 
                                            msg.toStructuredString());
                            if (MessageType.ARCHIVEDMSG == msg.messageType)
                                setServerNotResponding(false);
                        }
                    }
                        
                    boolean inSentMessages = false;
                    synchronized (sentMessages){
                        inSentMessages = sentMessageIds.containsKey(msg.messageId);
                    }
                    if ((MessageType.USERMSG == msg.messageType || MessageType.ARCHIVEDMSG == msg.messageType)
                            && ( ! "ACK".equals(msg.messageBody) &&
                                    // don't print messages that have been created while server offline and sent asynchronously
                                    (!inSentMessages))){
                        Logger.log (Level.INFO, msg.userName + " writes :: " + msg.messageBody);
                        receivedMessageIds.put(msg.messageId, null);
                        writeServerMessage(msg);
                        if (showPopups && 
                                MessageType.USERMSG == msg.messageType &&
                                tray != null) { // don't do it for archived messages
                            tray.setAlert("Nachricht von " + msg.userName + ": " + msg.messageBody);
                        }
                    } else if (MessageType.HEARTBEAT == msg.messageType){
                        Logger.log (Level.FINEST, "return heartbeat from server");
                    
                    } else if (MessageType.WHOSONLINE == msg.messageType){
                        Logger.log (Level.INFO, "Online users: " + msg.messageBody);
                        writeServerMessage(msg);
                    } else if (MessageType.ISTYPING == msg.messageType && ( ! "ACK".equals(msg.messageBody))){
                        writeServerMessage(msg);
                    }

                    lastMessageTime.set(msg.timeStamp);
                    
                } catch (SocketException se){
                    Logger.log(Level.WARNING, "Socket closed while reading");
                    shutdown();
                    setServerNotResponding(true);
                } catch (IOException ioe){
                    if (running)
                        Logger.log(Level.WARNING, "Reading from hub", ioe);
                    shutdown();
                    setServerNotResponding(true);
                }
            }
            
            Logger.log (Level.FINE, "RcveThread ends: " + this.toString());
            shutdown();
        }
        
        public void shutdown(){
            running = false;
        }
        
        public boolean isRunning() {
            return running;
        }
    }
    
    
    class HeartbeatThread extends TimerTask {
        
        public HeartbeatThread () {
            heartbeatsSent.set(0);
        }
        
        @Override
        public void run() {
            
            if (heartbeatsSent.get() >= HEARTBEAT_GRACE_PERIOD || (!sendThread.isRunning()) || (!receiveThread.isRunning())) {
                Logger.log(Level.FINER, "Last " + heartbeatsSent.get() +
                        " heartbeat(s) didn't get response from Server.");
                setServerNotResponding(true);

                try {
                    connect(lastMessageTime.get());
                    heartbeatsSent.set(0);
                } catch (IOException e){
                    Logger.log(Level.FINER, "heartbeatsSent > grace period, connect()", e);
                    System.exit(1);
                }
            } else {
                Logger.log (Level.FINEST, "Heartbeat pinging");
                heartbeatsSent.incrementAndGet();
                Message msg = new Message(userName, MessageType.HEARTBEAT, "RSVP");
                if (!messageQueue.offer(msg)){
                    Logger.log (Level.FINE, "Message queue full when adding heartbeat.");
                }
            }
        }
        
    }
    
    class UserInputThread extends Thread {
        
        private volatile boolean running = true;

        public UserInputThread (){
            setName("UInpThread");
        }
        
        @Override
        public void run() {
            super.run();
            
            while (running){
                String input = getUserInput();
                if (input != null && input.length() > 0){
                    // delete spurious ctrl-@ characters (\c0) which mess up the Message
                    StringBuffer sb = new StringBuffer(input);
                    for (int i = 0; i < sb.length(); i++){
                        if ((int)sb.charAt(i) == 0 || sb.charAt(i) == Message.FS) {
                            sb.deleteCharAt(i);
                            Logger.log(Level.FINEST, String.format("Removing invalid character at position %d", i));
                        }
                    }
                    input = sb.toString();

                    MessageType msgType = MessageType.USERMSG;
                    if (SECRET_WHO.equals(input)) {
                        msgType = MessageType.WHOSONLINE;
                    } else if (input.startsWith(SECRET_LOG)) {
                        String[] parts = input.split(" ");
                        if (parts.length == 2){
                            try {
                                Logger.setLogLevel(Level.parse(parts[1]));
                                Logger.log(Level.INFO, "Log level is now: " + parts[1]);
                            } catch (IllegalArgumentException iae) {
                                Logger.log(Level.WARNING, "Log level not recognised: " + parts[1]);
                            }
                            // don't send to server
                            continue;
                        }
                    } else if (input.equals(SECRET_STATS)) {
                        Logger.log(Level.WARNING, String.format("Sent total     %s in %5d messages", formatBytes(bytesSent), messagesSent));
                        Logger.log(Level.WARNING, String.format("Received total %s in %5d messages; altogether %10s", 
                            formatBytes(bytesReceived), messagesReceived, formatBytes(bytesReceived+bytesSent)));
                        // don't send to server
                        continue;
                    }
                    Logger.log (Level.FINE, "Adding user input to message queue: '" + input + "'");
                    if (usePayloadEncryption){
                        input = objCrypt.encryptString(input);
                    }
                    Message msg = new Message(userName, msgType, input);
                    if (!messageQueue.offer(msg)){
                        Logger.log (Level.WARNING, "Message queue full when adding user message.");
                    }
                }
            }
        }
        
        public void shutdown(){
            running = false;
        }
        
    }
    
    void setServerNotResponding(boolean notResponding) {
        if (serverNotResponding.get() != notResponding){
            serverNotResponding.set(notResponding);
            /*if (showPopups) {
                tray.setAlert("Server " + (notResponding ? "offline" : "online"));
            }*/
            if (notResponding) {
                if (tray != null) tray.setOffline();
            } else { 
                if (tray != null) tray.setOnline(); 
            }
        }
    }
    
    // only needed for Swing subclass
    public void toFront(){
    }
    
    // final because called from constructor
    public final void setPopups (boolean showPopups) {
        Logger.log (Level.FINER, "set showPopups to " + showPopups);
        this.showPopups = showPopups;
    }
    
    /*
     * Create secret key like this:
        SecretKey secretkey = KeyGenerator.getInstance("RC4").generateKey();
        encodedKey = new BASE64Encoder().encode(secretkey.getEncoded());
        
        Same key must then be used in Client
     */
    private void setEncryption (byte[] secretKey){
        byte[] raw;
        if (secretKey == null) {
            raw = defaultKey;
        } else {
            raw = secretKey;
        }
        this.useEncryption = true;
        Logger.log(Level.INFO, "Encryption: " + useEncryption);
        if (useEncryption){
            try {
                SecretKey key = new SecretKeySpec(raw, "RC4");
                decCipher = Cipher.getInstance("RC4");
                decCipher.init(Cipher.DECRYPT_MODE, key);
        
                encCipher = Cipher.getInstance("RC4");
                encCipher.init(Cipher.ENCRYPT_MODE, key);
            } catch (NoSuchAlgorithmException nsae){
                this.useEncryption = false;
                Logger.log(Level.WARNING, "NoSuchAlgorithmException, disabling encryption");
                return;
            } catch (InvalidKeyException ivke) {
                this.useEncryption = false;
                Logger.log(Level.WARNING, "InvalidKeyException, disabling encryption");
                return;
            } catch (GeneralSecurityException gse) {
                this.useEncryption = false;
                Logger.log(Level.WARNING, "GeneralSecurityException, disabling encryption");
                return;
            }
        }

    }

    
    static Options parseOptions(String[] args){
        Options options = new Options(args);
        
        if (options.optUser == null) {
            try {
                options.optUser = InetAddress.getLocalHost().toString() + ":" +
                        Math.ceil(Math.random()*1000);
                Logger.log(Level.FINE, "User: " + options.optUser);
            } catch (UnknownHostException e) {
                Logger.log(Level.WARNING, "Could not resolve host", e);
            }
        }
        
        return options;
        
    }

    private static String formatBytes(long bytes){
        double formatted = 0.0;
        String unit = "";
        String formatStr = "%10.2f %s";
        
        if (bytes < 1024 ) {
            unit = "bytes";
            formatted = bytes;
            formatStr = "%10.0f %s";
        } else if (bytes < 1024*1024) {
            unit = "KiB";
            formatted = bytes / 1024.0;
        } else if (bytes < 1024*1024*1024) {
            unit = "MiB";
            formatted = bytes / (1024.0 * 1024.0);
        } else if (bytes < 1024*1024*1024*1024) {
            unit = "GiB";
            formatted = bytes / (1024. * 1024. * 1024.);
        }

        return String.format (formatStr, formatted, unit);
    }
    
    public static void main(String[] args) {

        final Options options = parseOptions(args);
        if (options.optHost == null) options.optHost = "localhost";

        TextUI textui = null;
        try {
            textui = new TextUI(options);
            textui.connect(System.currentTimeMillis() - options.optArchiveMinutes * 60 * 1000L);
        } catch (IOException ioe) {
            Logger.log(Level.SEVERE, "IOException, could not create TextUI",
                    ioe);
        }
        
        if (textui == null) {
            Logger.log(Level.SEVERE, "TextUI could not be initialized.");
            System.exit(1);
        }

        Logger.log(Level.INFO, "End with Ctrl-D or Ctrl-C");
      
         Runtime.getRuntime().addShutdownHook(new Thread() {
          public void run() {
              Logger.log(Level.WARNING, "Entering shutdown hook");
              try {
                  if (options.optLogfile != null){
                      Logger.closeLogFile();
                  }
              } catch (IOException e) {
                  System.err.println("Could not close log file: " + e.getMessage());
              }
          }
         });
    }

}
