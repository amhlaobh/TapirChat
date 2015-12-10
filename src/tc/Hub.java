package tc;


import static tc.Message.MessageType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.logging.Level;

import tc.Message.MessageException;

/**
 * The central server process that handles connected clients, owns the server
 * socket and delegates incoming connections to {@link Client}s.
 * Data flowing in and out uses the Observer/Observable pattern in both directions.
 * @author ok
 * 
 */
public class Hub extends Observable implements Observer {


    // TODO: for MSG_TYPE_TYPING, increase Version
    static final String VERSION = "tc0.3";
    //private static final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.S z");
    private static final ThreadLocal<DateFormat> DATE_FORMAT =
        new ThreadLocal<DateFormat>() {
            @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss.S z");
                }
        };
    //private static final DateFormat dateTimeFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss.S z");
    // SimpleDateFormat is not thread safe, must use threadlocal
    private static final ThreadLocal<DateFormat> DATE_TIME_FORMAT =
        new ThreadLocal<DateFormat>() {
            @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss.S z");
                }
        };

    private static Hub hub;
    private static Options options;
    private static List<Message> messageArchive = Collections.synchronizedList(
            new ArrayList<Message>());
    private static final int MAX_MSG_CACHE_SIZE = 400;

    private static BufferedWriter journalWriter;

    static List<Client> clients = Collections.synchronizedList(
            new ArrayList<Client>());

    
    public Hub() {
        
        if (options.optJournalFile != null){
            // journal file holds only USER_MSG and lines for start/stop - which are ignored 
            // because they begin with "==="
            BufferedReader journalReader = null;
            try {
                journalReader = new BufferedReader(new FileReader(options.optJournalFile));
                String s = null;
                while ((s =journalReader.readLine()) != null) {
                    if (s.startsWith("===")) continue;
                    Message msg = null;
                    try {
                        msg = new Message(s);
                    } catch (MessageException me) {
                        Logger.log(Level.WARNING, "Could not parse as message: "+s);
                    }
                    if (msg != null) addMessageToArchive(msg);
                }
            } catch (IOException ioe){
                Logger.log(Level.WARNING, "Could not open journal file for reading: "+ 
                        options.optJournalFile.getAbsolutePath());
                journalWriter = null;
            } finally {
                try {
                    if (journalReader != null) journalReader.close();
                } catch (IOException e){
                    // ignore
                }
            }
            
            try {
                journalWriter = new BufferedWriter(new FileWriter(options.optJournalFile, true /*append*/));
                journalWriter.write("==========  Opened journal file " + 
                        DATE_TIME_FORMAT.get().format(System.currentTimeMillis()) + " ==========\n");
                journalWriter.flush();
            } catch (IOException ioe){
                Logger.log(Level.WARNING, "Could not open journal file for writing: " + 
                        options.optJournalFile.getAbsolutePath());
                journalWriter = null;
            }
        }

    }

    static class Server extends Thread {
        private ServerSocket serverSocket = null;
        private int port;
        private volatile boolean closed = false;
        
        public Server (int port) {
            
            this.port = port;
            this.setName("Hub");
            try{
                Logger.log(Level.FINE, "Registering server socket on port " + port);
                serverSocket = new ServerSocket(port);
            } catch (IOException ioe){
                Logger.log(Level.SEVERE, "Could not listen on port: "+port, ioe);
                throw new RuntimeException(ioe);
            }
            
        }

        @Override
        public void run() {
            accept ();
        }
        
        private void accept (){
            while (true) {
                Socket clientSocket = null;
                try {
                    if (!closed){
                        Logger.log(Level.FINE, "Listening");
                        clientSocket = serverSocket.accept();
                        if (!isIpAddressAllowed (clientSocket)){
                            clientSocket.close();
                        } else {
                            Client client = new Client(clientSocket, hub, options);
                            clients.add(client);
                            hub.addObserver(client);
                        }
                    }
                } catch (IllegalStateException ise){
                    Logger.log(Level.WARNING, "While creating Client: " + ise.getMessage());
                    try {
                        if (clientSocket != null) clientSocket.close();
                    } catch (IOException cse) {
                        cse.printStackTrace();
                    }
                } catch (SocketException se){
                    Logger.log(Level.FINE, "SocketException because of shutdown");
                    return;
                } catch (IOException e) {
                    Logger.log(Level.SEVERE, "Accept failed: "+port+ "  ", e);
                }
            }   
        }
        
        private boolean isIpAddressAllowed(Socket clientSocket) {
            if (options.optIpAddresses == null) {
                return true;
            } else {
                String clientAddress = clientSocket.getInetAddress().getHostAddress();
                for (String prefix : options.optIpAddresses) {
                    if (clientAddress.startsWith(prefix.trim())) {
                        return true;
                    }
                }
                Logger.log(Level.WARNING, "Connect from " + clientAddress + " not allowed!");
                return false;
            }
        }
        
        void shutdown(){
            try {
                closed = true;
                Logger.log(Level.FINE, "Closing server socket");
                serverSocket.close();
            } catch (IOException ioe){
                Logger.log(Level.SEVERE, "Shutdown not successful: ", ioe);
            }
        }
    }
    
    /** Inform all Clients of new messages.
     * @param arg a {@link Message}
     */
    //@Override
    public void update (Observable client, Object arg){
        if (!(arg instanceof Message)){
            Logger.log (Level.SEVERE, "Hub.update() got wrong Object type, was expecting Message");
        }
        
        Message msg = (Message) arg;
        Logger.log(Level.FINEST, "update :: " + msg.userName+"@"+msg.messageId+":" + msg.messageBody);
        if (MessageType.USERMSG == msg.messageType || MessageType.ISTYPING == msg.messageType){
            addMessageToArchive(msg);
            writeToJournal(msg);
            setChanged();
            notifyObservers(arg);
        }
    }
    
    private void writeToJournal (Message msg){
        if (journalWriter == null) return;
        try {
            journalWriter.write(msg.toStructuredString()+"\n");
            journalWriter.flush();
        } catch (IOException ioe) {
            Logger.log(Level.WARNING, "Could not write to journal file, closing it.");
            try {
                journalWriter.close();
            } catch (IOException e){
                // ignore
            }
            journalWriter = null;
        }
    }
    
    public void removeClient (Client client){
        if (clients.contains(client)){
            Logger.log(Level.FINE, "Removing client " + client);
            clients.remove(client);
            // TODO: send update message to all remaining clients MSG_TYPE_TYPING=false
            // Message noTypingMsg = new Message(client.userName, MSG_TYPE_TYPING, "false");
            //                     notifyObservers(noTypingMsg);

            deleteObserver(client);
        }
    }

    /** Return messages from now backwards with size "window".
     * 
     * @param interval
     * @return
     */
    public List<Message> getMessageArchive (long interval) {
        List<Message> result = new ArrayList<Message>();
        
        Logger.log(Level.FINEST, "getMessageArchive("+DATE_FORMAT.get().format(interval)+")");
        long now = System.currentTimeMillis();
        synchronized (messageArchive) {
            for (Message msg : messageArchive) {
                if (now - msg.timeStamp  < interval) {
                    // must clone Message, so that the original doesn't get modified
                    result.add(Message.createCopy(msg));
                }
            }
        }
        
        return result;
    }

    /** Return messages younger than "since".
     * 
     * @param since
     * @return
     */
    public List<Message> getMessageArchiveSince (long since) {
        List<Message> result = new ArrayList<Message>();

        Logger.log(Level.FINEST, "getMessageArchiveSince("+DATE_FORMAT.get().format(since)+")");
        synchronized (messageArchive) {
            for (Message msg : messageArchive) {
                if (msg.timeStamp > since) {
                    Logger.log(Level.FINEST, "Get archived message: " + msg.toStructuredString());
                    // must clone Message, so that the original doesn't get modified
                    result.add(Message.createCopy(msg));
                }
            }
        }
        
        return result;
    }

    /** Add message to message archive.
     * 
     * @param msg
     */
    // final because called from constructor
    public final void addMessageToArchive (Message msg){
        synchronized (messageArchive) {
            if (messageArchive.size() > MAX_MSG_CACHE_SIZE - 5) {
                messageArchive.remove(0);
            }
            messageArchive.add(msg);
        }
    }
    
    public static void main (String[] args){

        options = new Options(args);
        if (options.unknownOptionFound){
            options.printUsage();
            System.exit(1);
        }
        
        hub = new Hub();

        if (options.optHost == null){
            final Server hubThread = new Server(options.optPort);
            hubThread.start();

            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    hubThread.shutdown();
                    try {
                        if (options.optLogfile != null){
                            Logger.closeLogFile();
                        }
                    } catch (IOException e) {
                        System.err.println("Could not close log file: " + e.getMessage());
                    }
                    Logger.logStd(Level.SEVERE, "messageArchive size : " + messageArchive.size());

                    if (journalWriter != null){
                        try {
                            journalWriter.write("===.......  Closed journal file " + 
                                    DATE_TIME_FORMAT.get().format(System.currentTimeMillis()) + " ..........\n");
                            journalWriter.flush();
                        } catch  (IOException e) {
                            System.err.println("Could not finish journal file: " + e.getMessage());
                        }
                        try {
                            journalWriter.close();
                        } catch  (IOException e) {
                            System.err.println("Could not close journal file: " + e.getMessage());
                        }
                    }
                }
            });
        } else {
            // "-h" not valid for server mode
            Logger.log(Level.SEVERE, "Unexpected option -h");
        }
        
    }


}
