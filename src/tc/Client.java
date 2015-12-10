package tc;

import static tc.Message.MessageType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Represents a connection with a client (SwingUI or TextUI). Handles input and
 * output streams and informs the observer {@link Hub} of incoming data.
 * 
 * @author ok
 * 
 */
public class Client extends Observable implements Observer, Runnable {
    
    private static final long ARCHIVE_INTERVAL = 3600*1000L;
    
    static Level logLevel = Level.INFO;
    private static final String ENC_UTF8 = "UTF-8";
    private String userName = "";
    private long lastHeartbeat = System.currentTimeMillis();

    private Socket socket;
    private BufferedReader sendIsReader;
    private BufferedWriter sendOsWriter;
    private boolean useEncryption;
    private Cipher decCipher;
    private Cipher encCipher;
    private byte[] defaultKey = new byte[]{-25,101,-45,12,93,-38,-56,-7,-99,109,-83,78,-90,90,96,106};
    
    private volatile boolean inputStreamClosed;
    
    private Timer heartbeatTimer;
    
    private Hub hub;
    
    private int heartbeatInterval;
    
    public Client (Socket clientSocket, Hub hub, Options options) throws IllegalStateException{
        this.socket = clientSocket;
        this.hub = hub;
        addObserver(hub);
        
        if (options.optUseEncryption) setEncryption(options.optSecretKey);
        heartbeatInterval = options.optHeartbeat;
        
        Logger.log(Level.INFO, "================ New Client connection =========");

        try {
            Logger.log(Level.INFO, "IP address: " + clientSocket.getInetAddress());
            OutputStream sendOs = clientSocket.getOutputStream();
            InputStream sendIs = clientSocket.getInputStream();
            if (useEncryption){
                Logger.log(Level.FINE, "Initializing cipher streams");
                sendOsWriter = new BufferedWriter(new OutputStreamWriter(
                        new CipherOutputStream(sendOs, encCipher), ENC_UTF8));
                sendIsReader = new BufferedReader(new InputStreamReader(
                        new CipherInputStream(sendIs, decCipher), ENC_UTF8));
            } else {
                sendOsWriter = new BufferedWriter(new OutputStreamWriter(sendOs, ENC_UTF8));
                sendIsReader = new BufferedReader(new InputStreamReader(sendIs, ENC_UTF8));
            }

            Message versionMessage = null;
            try {
                clientSocket.setSoTimeout(options.optHeartbeat); 
                versionMessage = receiveMessage();
                if (versionMessage != null){
                    String rcvVer = versionMessage.messageBody;
                    if (! Hub.VERSION.equals(rcvVer)){
                        Message msg = new Message(versionMessage.userName, versionMessage.messageType, 
                                "Client's version wrong: " + rcvVer + " vs my " + Hub.VERSION, versionMessage.messageId); 
                        sendMessage (msg);
                        throw new IllegalStateException("Client's version wrong: " + rcvVer + " vs my " + Hub.VERSION);
                    } else {
                        Logger.log(Level.FINE, "  -- Sends version " + rcvVer);
                    }
                    sendMessage(Message.createAckMessage(versionMessage));
                } else {
                    throw new IllegalStateException ("Version string could not be determined (was empty).");
                }
            } catch (Message.MessageException iae) {
                Logger.log (Level.WARNING, "Expected version message.", iae);
                shutdown();
                throw new IllegalStateException ("Version string could not be determined.");
            }
            
            Message connectMessage = null;
            try {
                connectMessage = receiveMessage();
                if (connectMessage != null) {
                    Logger.log(Level.INFO, "  -- Connect: '" + connectMessage.toStructuredString() + "'");
                    sendMessage(Message.createAckMessage(connectMessage));
                } else {
                    throw new IllegalStateException("Expected connect message with client user name (but was empty).");
                }
            } catch (Message.MessageException iae) {
                throw new IllegalStateException("Expected connect message with client user name.", iae);
            }
            
            userName = connectMessage.messageBody;

            Logger.log(Level.INFO, "  -- User: '" + userName + "'");
            for (Client cl : Hub.clients){
                if (userName.equals(cl.userName)){
                    Logger.log(Level.WARNING, "user '" + userName + "' already known");
                    shutdown();
                    throw new IllegalStateException ("user '" + userName + "' already known, rejecting connection.");
                }
            }

            try {
                Message heartbeatMessage = receiveMessage();
                if (heartbeatMessage != null) {
                    Logger.log(Level.FINE, "  -- Heartbeat: '" + heartbeatMessage.toStructuredString() + "'");
                    sendMessage(Message.createAckMessage(heartbeatMessage));
                } else {
                    throw new IllegalStateException("Expected connect message with heartbeat interval (but was empty).");
                }
                
                try {
                    int hb = Integer.parseInt(heartbeatMessage.messageBody);
                    heartbeatInterval = hb;
                    Logger.log(Level.FINE, "  -- setting heartbeat interval to "+heartbeatInterval+"ms");
                } catch (NumberFormatException nfe){
                    throw new IllegalStateException("Heartbeat interval is not a number.", nfe);
                }
            } catch (Message.MessageException iae) {
                throw new IllegalStateException("Expected connect message with heartbeat interval.");
            }
            clientSocket.setSoTimeout(0); 

        } catch (IOException ioe) {
            Logger.log(Level.SEVERE, "While connecting: ",  ioe);
            shutdown();
            throw new IllegalStateException (ioe);
        }

        // TODO: better to do this outwith constructor, or make Client final
        Thread clientThread = new Thread(this);
        clientThread.setName(userName);
        clientThread.start();
        
        heartbeatTimer = new Timer(true);
        heartbeatTimer.schedule(new HeartbeatCheck(), heartbeatInterval, heartbeatInterval);

    }
    
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

    /**
     * Get data from network and create Message out of it.
     * @return Message or null if nothing was read or if stream was closed
     * @throws IOException
     * @throws Message.MessageException
     */
    private Message receiveMessage () throws IOException, Message.MessageException {
        StringBuilder msgStr = new StringBuilder();
        if (inputStreamClosed) return null;
        
        int c;
        while ((c = sendIsReader.read()) != -1 && c != Options.EOL){
            Logger.log(Level.FINEST, String.format("... %4d : %c", c, c));
            msgStr.append((char)c);
        }
        if (c == -1) {
            inputStreamClosed = true;
        }

        Message msg = null;
        if (msgStr.length() > 1){
            msg = new Message(msgStr.toString());
        }
        return msg;
    }
    
    private void sendMessage( Message msg) throws IOException {
        sendOsWriter.write(msg.toStructuredString());
        sendOsWriter.write(Options.EOL);
        sendOsWriter.flush();
        Logger.log (Level.FINEST, "Send message: " + msg.toStructuredString());
    }
    
    public void run(){
        receive();
    }
    
    // continually get and parse messages, then notify observers
    public void receive (){
        
        try{
            while (!inputStreamClosed){
                Message message = null;
                try { 
                    message = receiveMessage();
                } catch (Message.MessageException me) {
                    message = null;
                    Logger.log(Level.WARNING, "Expected message, was null.");
                    continue;
                }

                if (message == null || message.messageType == null) continue;
                Logger.log(Level.FINEST, "Recv message: "+message.toStructuredString());
                
                Message backMsg = null;
                if (MessageType.USERMSG == message.messageType || MessageType.ISTYPING == message.messageType){
                    Logger.log (Level.FINEST, "  -- rcv::"+message.toStructuredString());
                    lastHeartbeat = System.currentTimeMillis();
                    setChanged();
                    // notify hub
                    notifyObservers(message);

                    backMsg = Message.createAckMessage(message);
                    
                } else  if (MessageType.HEARTBEAT == message.messageType){
                    //Logger.log (Level.FINEST, "  -- rcv::user="+userName+"- heartbeat");
                    lastHeartbeat = System.currentTimeMillis();
                    backMsg = Message.createAckMessage(message);
                    
                } else  if (MessageType.ARCHIVE == message.messageType){
                    List<Message> archive = null;
                    if (!"latest".equals(message.messageBody)) { // messages since time x
                        archive = hub.getMessageArchiveSince(Long.parseLong(message.messageBody));
                    } else { // messages of age ARCHIVE_INTERVAL or younger
                        archive = hub.getMessageArchive(ARCHIVE_INTERVAL);
                    }
                    for (Message msg : archive) {
                        msg.messageType = MessageType.ARCHIVEDMSG;
                        sendMessage (msg);
                        //try {Thread.sleep(100);} catch (InterruptedException e) {}
                    }
                    
                } else  if (MessageType.WHOSONLINE == message.messageType){
                    StringBuilder sb = new StringBuilder();
                    for (Client c : Hub.clients){
                        sb.append(c.userName).append("; ");
                    }
                    Message msg = new Message(userName, MessageType.WHOSONLINE, sb.toString()); 
                    sendMessage (msg);
                    
                } else  if (MessageType.SHUTDOWN == message.messageType){
                    Logger.log (Level.FINE, userName + " sends Shutdown");
                    shutdown();
                }
                
                if (backMsg != null){
                    sendMessage (backMsg);
                }

            }
        } catch (SocketException se){
            Logger.log(Level.INFO, "Client has disconnected: " + userName);
            shutdown();
        } catch (IOException ioe) {
            Logger.log(Level.SEVERE, "Sending/receiving message: ",  ioe);
        } finally {
            Logger.log(Level.INFO, "Client has finished: " + userName);
            shutdown();
        }
    }
    
    //@Override
    public void update (Observable client, Object arg){
        if (!(arg instanceof Message)){
            Logger.log (Level.SEVERE, "Client.update() got wrong Object type, was expecting Message");
        }
        
        try{
            Message msg = (Message) arg;
            if (userName.equals(msg.userName)){
                // don't copy myself
                return;
            }
            Logger.log(Level.FINER, "Schreibe an '"+userName+"': "+msg.messageBody);
            sendMessage(msg);
        } catch (IOException ioe){
            Logger.log(Level.WARNING, "closed, remove myself from Hub " + this.toString(), ioe);
            shutdown();
        }
    }
    
    private void shutdown(){
        hub.removeClient(Client.this);
        inputStreamClosed = true;
        if (heartbeatTimer != null) heartbeatTimer.cancel();

        try {
            if (sendOsWriter != null) sendOsWriter.close();
        } catch (IOException e) {
            Logger.log (Level.FINE, "Client shutdown: " + e);
        }
        try {
            if (sendIsReader != null) sendIsReader.close();
        } catch (IOException e) {
            Logger.log (Level.FINE, "Client shutdown: " + e);
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            Logger.log (Level.FINE, "Client shutdown: " + e);
        } 
        
    }
    
    @Override
    public String toString(){
        return "'"+userName+"'";
    }

    private class HeartbeatCheck extends TimerTask {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastHeartbeat > heartbeatInterval * 2) {
                Logger.log (Level.WARNING, "Client " + userName + 
                        " has not sent a heartbeat within " + heartbeatInterval*2 + "ms");
                shutdown();
            }
        }   
    }

}
