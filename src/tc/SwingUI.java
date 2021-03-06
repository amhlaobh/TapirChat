package tc;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Frame;
import java.awt.IllegalComponentStateException;
import java.awt.Image;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

import tc.Message.MessageType;

public class SwingUI extends TextUI {
    
    //private static final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    private static final ThreadLocal<DateFormat> DATE_FORMAT =
        new ThreadLocal<DateFormat>() {
            @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss");
                }
        };
    private static final Color INPUT_AREA_COLOR_OK = Color.GREEN;
    private static final Color INPUT_AREA_COLOR_OFFLINE = Color.GRAY;
    private static final String STYLE_STATUS = "status";
    private static final String STYLE_LOCAL = "local";
    private static final String STYLE_SERVER = "server";
    
    private JTextPane conversationPane;
    private StyledDocument conversationDoc;
    private StyledDocument notificationDoc;
    private JTextArea inputArea;
    private JTextPane notificationField;
    
    private JFrame swingUIFrame;
    private String newInput;
    
    // must be static so we can create SwingUI on EDT _and_ use field swingui later to connect
    static SwingUI swingui;

    private static final ThreadLocal<DateFormat> DATE_TIME_FORMAT =
        new ThreadLocal<DateFormat>() {
            @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss z 'am' dd.MM.yyyy ");
                }
        };
    
    public SwingUI (Options options) throws IOException {
        super(options);

        final JScrollPane scrollPane;
        
        swingUIFrame = new JFrame();
        swingUIFrame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent we) {
                Logger.log(Level.INFO, "Window closed, exiting.");

                shutdownUI();
                System.exit(0);
            }
        });

        swingUIFrame.setTitle(userName);
        
        Image tapirImage = null;
        try {
            URL imgURL = ClassLoader.getSystemClassLoader().getClass().getResource("/img/Tapir01.png");
            Logger.log(Level.FINER, "Loading /img/Tapir01.png");
            tapirImage = new ImageIcon(imgURL, "Tapir image").getImage();
            if (tapirImage == null){
                Logger.log(Level.WARNING, "Tapir image wasn't loaded from /img/Tapir01.png");
            }
        } catch (Exception e1) {
            Logger.log(Level.WARNING, "", e1);
        }
        swingUIFrame.setIconImage(tapirImage);
        
        swingUIFrame.getContentPane().setLayout(new BorderLayout(5,5));

        inputArea = new JTextArea();
        swingUIFrame.add(inputArea, BorderLayout.SOUTH);
        
        conversationPane = new JTextPane(); 
        conversationPane.setEditable(false);
        conversationDoc = conversationPane.getStyledDocument();
        
        notificationField = new JTextPane();
        notificationField.setEditable(false);

         
        Logger.log(Level.CONFIG, "Default font: " + notificationField.getFont().toString());
        if (options.optFont != null) {
            inputArea.setFont(options.optFont);
            conversationPane.setFont(options.optFont);
        }

        Style defaultStyle = StyleContext.getDefaultStyleContext().
            getStyle(StyleContext.DEFAULT_STYLE);
        Style s = conversationDoc.addStyle(STYLE_SERVER, defaultStyle);
        StyleConstants.setBold(s, true);

        s = conversationDoc.addStyle(STYLE_LOCAL , defaultStyle);
        StyleConstants.setBold(s, false);

        s = conversationDoc.addStyle(STYLE_STATUS , defaultStyle);
        StyleConstants.setItalic(s, true);
        StyleConstants.ColorConstants.setForeground(s, Color.lightGray);

        notificationDoc = notificationField.getStyledDocument();
        s = notificationDoc.addStyle(STYLE_STATUS , defaultStyle);
        StyleConstants.setItalic(s, true);
        StyleConstants.ColorConstants.setForeground(s, Color.lightGray);
        
        
        scrollPane = new JScrollPane(conversationPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        
        JScrollPane notificationScrollPane = new JScrollPane(notificationField,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        
        inputArea.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent event) {
              if(event.getKeyCode() == KeyEvent.VK_ENTER && 
                      ((event.getModifiers() & KeyEvent.SHIFT_MASK) != KeyEvent.SHIFT_MASK)) {
                  newInput = inputArea.getText();
                  inputArea.setText("");
              } else if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                  inputArea.insert("\n", inputArea.getCaretPosition());
              }
              
              //TypingTimer.resetCountdown(SwingUI.this);
          }
        });
        
        conversationPane.addMouseListener(new MouseAdapter() {

            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                if (tray != null) tray.resetAlert();
            }
            
        });

        inputArea.addMouseListener(new MouseAdapter() {

            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                if (tray != null) tray.resetAlert();
            }
            
        });

        swingUIFrame.add(scrollPane, BorderLayout.CENTER);
        swingUIFrame.add(notificationScrollPane, BorderLayout.NORTH);
        
        swingUIFrame.setSize(400, 400);
        swingUIFrame.setVisible(true);
        inputArea.requestFocusInWindow();
        inputArea.setBackground(INPUT_AREA_COLOR_OFFLINE);
        inputArea.setLineWrap(true);
        inputArea.setRows(2);
        
        Timer whosOnlineTimer = new Timer (true);
        whosOnlineTimer.schedule(this.new WhosOnlineThread(), 1000, options.optHeartbeat);
    }
    
    private static class TypingTimer {
        private static Timer timer = new Timer("TypingTimer", true /*isDaemon*/);
        private static TimerTask timerTask;
        private static boolean typingStarted;
        
        public static synchronized void resetCountdown(SwingUI ui){
            if (!typingStarted) {
                sendStartMessage(ui);
                typingStarted = true;
                Logger.log(Level.FINER, "Start!");
            } else {
                Logger.log(Level.FINER, "Reset!");
            }
            if (timerTask != null)
                timerTask.cancel();
            timerTask = createStopTask(ui);
            timer.schedule(timerTask, new Date(System.currentTimeMillis() + 8000));
        }
        
        public static synchronized void stopCountdown(SwingUI ui){
            sendStopMessage(ui);
            if (timerTask != null)
                timerTask.cancel();
            timerTask = null;
            typingStarted = false;
        }
        
        private static void sendStartMessage (SwingUI ui) {
            if (ui == null) return;
            
            Message typingMessage = new Message(ui.userName, MessageType.ISTYPING, "true");
            ui.messageQueue.offer(typingMessage);
        }

        private static void sendStopMessage (SwingUI ui) {
            if (ui == null) return;

            Message typingMessage = new Message(ui.userName, MessageType.ISTYPING, "false");
            ui.messageQueue.offer(typingMessage);
        }
        
        private static TimerTask createStopTask (final SwingUI ui) {
            return new TimerTask() {

                @Override
                public void run() {
                    Logger.log(Level.FINER, "Rrring!");
                    stopCountdown(ui);
                }
              
          };
        }
    }

    @Override
    String getUserInput(){
        String rv = null;
        try {
            Thread.sleep (100);
            while (newInput == null){
                Thread.sleep (100);
            }
            rv = newInput;
            newInput = null;
            //Logger.log(Level.CONFIG, "isEventDispatchThread: "+javax.swing.SwingUtilities.isEventDispatchThread());
            Runnable runInEventDispatchThread = new Runnable() {  
                public void run() {     
                    inputArea.setText("");
                }
            };
            SwingUtilities.invokeLater(runInEventDispatchThread);  
            Logger.log(Level.INFO, "user input :: " + rv);
            //TypingTimer.stopCountdown(this);
            writeConversationMessage(new Message(userName, MessageType.USERMSG, rv), STYLE_LOCAL);
            Runnable runInEventDispatchThread1 = new Runnable() {  
                public void run() {     
                    inputArea.requestFocusInWindow();
                }
            };
            SwingUtilities.invokeLater(runInEventDispatchThread1);  
        } catch (InterruptedException e){}
        return rv;
    }
    
    private void writeConversationMessage(final Message msg, final String style){
        while ((conversationPane == null) || (conversationDoc == null)) {
            try {Thread.sleep(1000);} catch (InterruptedException e) {}
            // might not be available because constructor must finish first
            Logger.log(Level.FINER, "Wait for ui");
        }

        Runnable updateConversationPane = new Runnable() {  
            public void run() {     
                String tmpStyle = style;
                conversationPane.setCaretPosition(conversationDoc.getLength());
                if  (msg.messageBody.startsWith(SECRET_LOG+" ") || msg.messageBody.equals(SECRET_WHO)
                        || msg.messageBody.equals(SECRET_STATS)){
                    tmpStyle = STYLE_STATUS ;
                }
                try {
                    Style s = conversationDoc.getStyle(tmpStyle);
                    
                    conversationDoc.insertString( conversationDoc.getLength(), 
                            "[" + DATE_FORMAT.get().format(msg.timeStamp) + "] " + 
                            msg.userName+": "+msg.messageBody + 
                            "\n", s);
                } catch (BadLocationException ble){
                    //
                }
                conversationPane.setCaretPosition(conversationDoc.getLength());
            }  
        };  
        SwingUtilities.invokeLater(updateConversationPane);  

    }
    
    @Override
    void writeServerMessage(final Message message){
        if (message.messageType == MessageType.WHOSONLINE){
            //writeConversationMessage(message, STYLE_STATUS );
            Runnable runInEventDispatchThread = new Runnable() {  
                public void run() {     
                    try {
                        Logger.log(Level.FINER, "Updating notification area with Online status");
                        notificationField.setCaretPosition(0);
                        notificationField.setText("");
                        notificationDoc.insertString( 0, "Online: "+message.messageBody, 
                            notificationDoc.getStyle(STYLE_STATUS ));
                    } catch (BadLocationException ble){
                        //
                    }
                }
            };
            SwingUtilities.invokeLater(runInEventDispatchThread);  
            //notificationField.setText("Online: "+message.messageBody);
        /*
        } else if (message.messageType == MessageType.ISTYPING) {
            if ("false".equals(message.messageBody)) {
                notificationField.setText("");
            } else {
                notificationField.setText(message.userName + " tippt");
            }
            */
            // TODO: show in notification area
        } else {
            if (usePayloadEncryption){
                message.messageBody = objCrypt.decryptString(message.messageBody);
            }
            if (userName.equals(message.userName)){
                writeConversationMessage(message, STYLE_LOCAL);
            } else {
                writeConversationMessage(message, STYLE_SERVER);
            }
        }
        inputArea.requestFocusInWindow();
    }
    
    @Override
    public void toFront(){
        Logger.log(Level.FINEST, "Trying to bring SwingUI to front");
        Runnable runInEventDispatchThread = new Runnable() {  
            public void run() {     
                if(swingUIFrame.getState()!=Frame.NORMAL) { 
                    swingUIFrame.setState(Frame.NORMAL); 
                }
                Point upperLeft = null;
                try {
                    upperLeft = swingUIFrame.getLocationOnScreen();
                } catch (IllegalComponentStateException e) {
                    
                }
                swingUIFrame.setVisible(false);
                // this sleep seems to be necessary for setLocation to work, otherwise frame will be 
                //   shown in top left corner of screen
                try{Thread.sleep (100);}catch (InterruptedException e){}
                if (upperLeft != null) swingUIFrame.setLocation(upperLeft);
                swingUIFrame.setVisible(true);
                swingUIFrame.toFront();
            }
        };
        SwingUtilities.invokeLater(runInEventDispatchThread);  
    }

    @Override
    void setServerNotResponding(final boolean notResponding) {
        boolean oldStatus = serverNotResponding.get();
        super.setServerNotResponding(notResponding);
        if (oldStatus != notResponding) {
            //inputArea.setEditable(!notResponding);
            Runnable runInEventDispatchThread = new Runnable() {  
                public void run() { 
                    inputArea.setBackground(notResponding ? INPUT_AREA_COLOR_OFFLINE : INPUT_AREA_COLOR_OK);
                    notificationField.setCaretPosition(0);
                    notificationField.setText("");
                    try{
                        if (notResponding){
                            Logger.log(Level.FINER, "Updating notification area with Offline status");
                            notificationDoc.insertString( 0, "Offline seit: "+DATE_TIME_FORMAT.get().format(System.currentTimeMillis()),
                                notificationDoc.getStyle(STYLE_STATUS));
                        } else  {
                            // only if this was previously offline
                            Logger.log(Level.FINER, "Updating notification area with Online status (setServerNotResponding)");
                            notificationDoc.insertString( 0, "Online seit: "+DATE_TIME_FORMAT.get().format(System.currentTimeMillis()),
                                notificationDoc.getStyle(STYLE_STATUS));
                        }
                    } catch (BadLocationException ble){
                        //
                    }
                    //writeConversationMessage(new Message (user, MSG_TYPE_USER, "Server is offline"), STYLE_SERVER);
                }
            };
            SwingUtilities.invokeLater(runInEventDispatchThread);  
        }
    }

    class WhosOnlineThread extends TimerTask {
        
        public WhosOnlineThread () {
        }
        
        @Override
        public void run() {
            
            if ( ! serverNotResponding.get() ){
                Logger.log (Level.FINEST, "WhosOnline pinging");
                Message msg = new Message(userName, MessageType.WHOSONLINE, "WHO");
                if (!messageQueue.offer(msg)){
                    Logger.log (Level.FINE, "Message queue full when adding whosonline message.");
                }
            }
        }
        
    }
    
    public static void main(String[] args){
        
        final Options options = parseOptions(args);
        if (options.optHost == null) options.optHost = "localhost";

        Logger.log(Level.FINE, "Time zone is " + TimeZone.getDefault().getDisplayName(true, TimeZone.LONG, Locale.ENGLISH) +
                " (UTC+"+TimeZone.getDefault().getOffset(System.currentTimeMillis())/1000/3600 + ")");
        Logger.log(Level.FINE, "End with Ctrl-C");
        
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

        // good practice to initialise GUI on Event Dispatch Thread
        try {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try{
                    swingui = new SwingUI(options);
                } catch (IOException ioe){
                    Logger.log(Level.SEVERE, "IOException, could not create SwingtUI", ioe);
                    ioe.printStackTrace();
                    System.exit(1);
                }
            }
        });
        } catch (InterruptedException ie){
            Logger.log(Level.WARNING, "gui creation interrupted", ie);
        } catch (java.lang.reflect.InvocationTargetException ite){
            Logger.log(Level.WARNING, "gui creation invocationtargetex.", ite);
        }
        try {
            swingui.connect(System.currentTimeMillis()-options.optArchiveMinutes*60*1000L);
        } catch (IOException ioe){
            Logger.log(Level.SEVERE, "IOException, could not create SwingtUI", ioe);
            ioe.printStackTrace();
            System.exit(1);
        }
        
    }

}
