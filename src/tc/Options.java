package tc;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.logging.Level;

// if options logging is to be shown, "-l FINE" must be the FIRST option!
public final class Options {
    public static final byte EOL = 0;

    public Integer optPort = 64321;
    public String optHost;
    public String optUser;
    public String[] optIpAddresses;
    public Integer optLocalPort = 0;
    public Boolean optUseEncryption = true;
    public Boolean optUsePayloadEncryption = false;
    public Integer optArchiveMinutes = 720;
    public Font optFont;
    public float optFontSize = 11.0f;
    public File optLogfile;
    public File optJournalFile;
    public Boolean optPopups = true;
    public byte[] optSecretKey;
    public Integer optHeartbeat = 30*1000;
    public boolean unknownOptionFound = false;

    public Options (String[] args) {
        Deque<String> deq = new ArrayDeque<String>(Arrays.asList(args));
        
        while(!deq.isEmpty()){
            String opt = deq.pop();
            if (opt.startsWith("-")){
                
                // First the options without argument
                if (opt.startsWith("-c")) {
                    optUseEncryption = true;
                    Logger.log (Level.CONFIG, "Enabling encryption.");
                } else if (opt.startsWith("-help")) {
                    printUsage();
                    System.exit(0);
                } else if (opt.startsWith("-noc")) {
                    optUseEncryption = false;
                    Logger.log (Level.CONFIG, "Disabling encryption.");
                } else if (opt.startsWith("-pc")) {
                    optUsePayloadEncryption = true;
                    Logger.log (Level.CONFIG, "Enabling payload encryption.");
                } else if (opt.startsWith("-nopc")) {
                    optUsePayloadEncryption = false;
                    Logger.log (Level.CONFIG, "Disabling payload encryption.");
                }  else if (opt.startsWith("-nopo")) {
                    optPopups = false;
                    Logger.log (Level.CONFIG, "Disabling popups");
                } else {
                    
                    // Now options with argument
                    if (deq.isEmpty()) {
                        // second part of -x <option> missing
                        Logger.log (Level.WARNING, "Expected 1 argument for : " + opt);
                        unknownOptionFound = true;
                        break;
                    }
                
                    String parm = deq.poll();
                    // doesn't work with -k: argument list may start with negative number
                    if (opt.startsWith("-k") && parm.startsWith("-")) {
                        Logger.log(Level.SEVERE, "Argument for "+opt+" must not start with '-': " + parm);
                        unknownOptionFound = true;
                        break;
                    }
                    if (opt.startsWith("-p")){ // Port
                        try {
                            optPort = Integer.parseInt(parm);
                        } catch (NumberFormatException nfe){
                            Logger.log(Level.SEVERE, "Could not parse port "+parm);
                            throw new RuntimeException("Could not parse port "+parm);
                        }
                    } else if (opt.startsWith("-h")){ // Receiver Host
                        optHost = parm;
                    } else if (opt.startsWith("-u")){ // user
                        optUser = parm;
                    } else if (opt.startsWith("-i")){ // allowed ip addresses that can connect
                        optIpAddresses = parm.split(",");
                    } else if (opt.startsWith("-l")){ // log level
                        if (parm.startsWith("FINEST")){
                            Logger.setLogLevel(Level.FINEST);
                        } else if (parm.startsWith("FINER")){
                            Logger.setLogLevel(Level.FINER);
                        } else if (parm.startsWith("FINE")){
                            Logger.setLogLevel(Level.FINE);
                        } else if (parm.startsWith("CONFIG")){
                            Logger.setLogLevel(Level.CONFIG);
                        } else if (parm.startsWith("INFO")){
                            Logger.setLogLevel(Level.INFO);
                        } else if (parm.startsWith("WARNING")){
                            Logger.setLogLevel(Level.WARNING);
                        } else if (parm.startsWith("SEVERE")){
                            Logger.setLogLevel(Level.SEVERE);
                        } else {
                            Logger.log (Level.WARNING, "Unknown log level: " + parm);
                        }
                        Logger.log (Level.CONFIG, "Setting log level to " + Logger.getLevel());
                    } else if (opt.startsWith("-r")) {
                        try {
                            optLocalPort = Integer.parseInt(parm);
                            Logger.log (Level.CONFIG, "Setting local port to " + parm);
                        } catch (NumberFormatException nfe){
                            Logger.log(Level.SEVERE, "Could not parse local port "+parm);
                            throw new RuntimeException("Could not parse local port "+parm);
                        }
                    }  else if (opt.startsWith("-a")) {
                        try {
                            optArchiveMinutes = Integer.parseInt(parm);
                        } catch (NumberFormatException nfe){
                            Logger.log(Level.SEVERE, "Could not parse archive minutes "+parm);
                            throw new RuntimeException("Could not parse archive minutes "+parm);
                        }
                        Logger.log (Level.CONFIG, "Setting number of minutes of archive to request to " + parm);
                    } else if (opt.startsWith("-b")) {
                        try {
                            optHeartbeat = Integer.parseInt(parm) * 1000;
                        } catch (NumberFormatException nfe){
                            Logger.log(Level.SEVERE, "Could not parse heartbeat interval "+parm);
                            throw new RuntimeException("Could not parse heartbeat interval "+parm);
                        }
                        Logger.log (Level.CONFIG, "Setting heartbeat interval to " + parm + " seconds");
                    } else if (opt.startsWith("-fo")) {
                        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                        String fontName = parm;
                        if (ge != null){
                            try {
                                Font[] allFonts = ge.getAllFonts();
                                for (Font f : allFonts){
                                    Logger.log(Level.FINEST, "Font on system: "+f.getName());
                                    if (fontName.equals(f.getName())){
                                        optFont = f.deriveFont(optFontSize);
                                        Logger.log(Level.CONFIG, "Found font '" + optFont.getName() + "'");
                                        break;
                                    }
                                }
                            } catch (NullPointerException npe) {
                            }
                        }
                        if (optFont == null) {
                            Logger.log(Level.WARNING, "Could not find font " + fontName + " on this machine");
                        } else {
                            Logger.log (Level.CONFIG, "Setting font to " + parm);
                        }
                    } else if (opt.startsWith("-fs")) {
                        try {
                            optFontSize = Float.parseFloat(parm);
                        } catch (NumberFormatException nfe) {
                            Logger.log(Level.WARNING, "Could not parse font size: " + parm);
                            break;
                        }
                        Logger.log (Level.CONFIG, "Setting font size to " + optFontSize );
                    } else if (opt.startsWith("-k")) {
                        // 16 bytes -128 .. 127
                        String[] bytes = parm.split(",");
                        if (bytes.length != 16) {
                            Logger.log(Level.SEVERE, "Expected 16 bytes for secret key");
                            unknownOptionFound = true;
                            break;
                        }
                        int i = 0;
                        optSecretKey = new byte[16];
                        try {
                            for (String s : bytes){
                                optSecretKey[i] = Byte.parseByte(s);
                                i++;
                            }
                        } catch (NumberFormatException nfe) {
                            Logger.log(Level.SEVERE, "Invalid byte value: " + bytes[i]);
                            unknownOptionFound = true;
                            break;
                        }
                        Logger.log (Level.CONFIG, "Setting secret key to " + parm );
                    } else if (opt.startsWith("-g")) {
                        optLogfile = new File(parm);
                        Logger.log (Level.CONFIG, "Setting log file to " + parm);
                        try {
                            Logger.setLogFile(optLogfile);
                        } catch (IOException e) {
                            System.err.println("Could not open log file for writing: " + parm);
                            optLogfile = null;
                        }
                    } else if (opt.startsWith("-j")) {
                       optJournalFile = new File(parm);
                       Logger.log (Level.CONFIG, "Setting journal file to " + parm);
                    } else {
                        Logger.log (Level.WARNING, "Unknown option: " + opt);
                        unknownOptionFound = true;
                    }
                }
            } else { 
                Logger.log (Level.WARNING, "Unknown option: " + opt);
                unknownOptionFound = true;
                break;
                    
            }
        }

    }

    public void printUsage(){
        System.out.println("Available options:");
        System.out.println("  -help this message");
        System.out.println("  -g <log file>   (should be first command line option; '-' for stdout (default))");
        System.out.println("  -j <journal file>   (only for Hub; logs all user messages)");
        System.out.println("  -l [FINEST|FINER|FINE|INFO|WARNING|SEVERE]  -> log level, should be first or second option");
        System.out.println("  -h <server host>    (only for TextUI or SwingUI)");
        System.out.println("  -p <server port, default " + optPort);
        System.out.println("  -u <user name>      (only for TextUI or SwingUI)");
        System.out.println("  -r <local port>     (only for TextUI or SwingUI)");
        System.out.println("  -c | -noc   -> use|disable encryption");
        System.out.println("  -pc | -nopc -> use|disable payload encryption");
        System.out.println("  -nopo       -> disable popup balloons");
        System.out.println("  -i <ip address[,ip address]>  -> allowed ip addresses");
        System.out.println("  -a <minutes>   -> how many minutes' worth of archive to request (default 30)");
        System.out.println("  -b <seconds>   -> heartbeat interval (default 30 seconds)");
        System.out.println("  -k <secret key>  -> 16 bytes in the format -33,-59,-1,106,... ; values: -128 .. 127");
        System.out.println("  -fs <font size>  -> font size, can be float; default: 11.0; must come before -fo");
        System.out.println("  -fo <font>       -> font; must be full name; list all fonts with -l FINEST -fo x");
        System.out.println("Call as: java -cp tc.jar tc.Hub     -> Server mode");
        System.out.println("         java -cp tc.jar tc.TextUI  -> text interface");
        System.out.println("         java -cp tc.jar tc.SwingUI -> graphical interface");
        System.out.println("         java -jar tc.jar           -> same as tc.Hub");
        System.out.println("         java -Djava.awt.headless=true  -> if no System Tray notification wished");
        System.out.println("         java -cp tc.jar tc.Hub -i 192.168,10.,172.16.  -> only accept local ip addresses");
        
    }

}
