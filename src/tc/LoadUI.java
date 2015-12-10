package tc;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
public class LoadUI extends TextUI {
    
    static final Random RANDOM = new Random();
    
    // sentences might be accessed by userinputthread and main thread
    // TODO: better to start userinputthread outwith constructor
    private volatile List<String> sentences = null;

    public LoadUI (Options options) throws IOException {
        super(options);
    }
    
    // log received text onto console
    void writeServerMessage(Message message){
        if (usePayloadEncryption){
            message.messageBody = objCrypt.decryptString(message.messageBody);
        }
        Logger.log(Level.FINEST, message.userName+"# "+message.messageBody);
    }

    synchronized String getUserInput(){
        if (sentences == null){
            sentences = Collections.synchronizedList(new ArrayList<String>());
            BufferedReader sentenceReader = null;
            try {
                sentenceReader = new BufferedReader (new FileReader("/homes/ok/tmp/alice.txt"));
                String input;
                while ((input = sentenceReader.readLine()) != null){
                    if (sentences == null) {System.err.println(" loop sentences null");
                    Logger.logStd(Level.SEVERE, " loop sentences null");}
                    
                    sentences.add(input);
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
                System.exit(1);
            } finally {
                try {
                    if (sentenceReader != null) sentenceReader.close();
                } catch (IOException e){
                    //
                }
            }
        }

        //if (random == null) System.err.println("random null");
        try {
            Thread.sleep (RANDOM.nextInt(1000));
        } catch  (InterruptedException e){}
        
        if (sentences == null){ System.err.println("sentences null");
        Logger.logStd(Level.SEVERE, " sentences null");}
        
        // delete spurious ctrl-@ characters (\c0) which mess up the Message
        StringBuffer sb = new StringBuffer(sentences.get(RANDOM.nextInt(sentences.size()-1)));
        for (int i = 0; i < sb.length(); i++){
            if ((int)sb.charAt(i) == 0) sb.deleteCharAt(i);
        }
        String input = sb.toString();

        return input;
    }
    
    public static void main (String[] args) {
        final Options options = new Options(args);
        options.optHost = "localhost";
        options.optPopups = false;
        options.optUser = "loadui " + RANDOM.nextInt(9999);
        LoadUI loadui = null;
        try {
            loadui = new LoadUI(options);
            loadui.connect(System.currentTimeMillis());
        } catch (Exception e){
            System.err.println(e);
            if (loadui != null)
                loadui.shutdownUI();
            System.exit(1);
        }
        
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                Logger.log(Level.INFO, "Entering shutdown hook");
                try {
                    if (options.optLogfile != null){
                        try {
                            // give all threads the chance to write last entries
                            Thread.sleep (1000);
                        } catch (InterruptedException e){}
                        Logger.closeLogFile();
                    }
                } catch (IOException e) {
                    System.err.println("Could not close log file: " + e.getMessage());
                }
            }
           });

        
        try {
//            Thread.sleep (random.nextInt(500000));
            Thread.sleep (86400000);
        } catch (InterruptedException e){}
        
        
        
        Logger.log(Level.INFO, "LoadUI closing");
        if (loadui != null)
            loadui.shutdownUI();
        System.exit(0);
        
        
        
    }

    
}
