package tc;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.logging.Level;

public final class Logger {
    
    private static Level logLevel = Level.INFO;
    private static Writer logWriter;
    //private static final DateFormat timeFormat = new SimpleDateFormat("d.MM HH:mm:ss.S");
    private static final ThreadLocal<DateFormat> TIME_FORMAT =
        new ThreadLocal<DateFormat>() {
            @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("d.MM HH:mm:ss.S");
                }
        };
    private static final ThreadLocal<DateFormat> DATE_TIME_FORMAT =
        new ThreadLocal<DateFormat>() {
            @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss.S z");
                }
        };
    static {
        TIME_FORMAT.get().setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    // hidden utility class constructor 
    private Logger(){
    }

    /** constructs a log entry with date tag from a message */
    private static String dolog (Level level, String s){
        StringBuilder output = new StringBuilder()
        .append("[")
        .append(String.format("%-7s", level.toString())).append('|')
        .append(String.format("%-10s", Thread.currentThread().getName())).append('|')
        .append(String.format("%-18s", TIME_FORMAT.get().format(System.currentTimeMillis())))
        .append("]: ")
        .append(s).append(' ')
        .append(System.getProperty("line.separator"));
        return output.toString();
    }
    
    /** constructs a log entry with date tag from a message and exception */
    private static String dolog (Level level, String s, Exception e){
        return dolog(level, s + " |  " + e.toString());
    }
    
    private static void doWriteOut (String s) {
        if (logWriter != null) {
            try {
                logWriter.write(s);
                logWriter.flush();
            } catch (IOException ioe) {
                System.err.println("Could not write to log file: " + s);
            }
        } else {
            System.out.print(s);
        }
    }

    private static void doWriteErr (String s) {
        if (logWriter != null) {
            try {
                logWriter.write(s);
                logWriter.flush();
            } catch (IOException ioe) {
                System.err.println("Could not write to log file: " + s);
            }
        } else {
            System.err.print(s);
        }
    }
    

    static void log (Level level, String s){
        if (level.intValue() >= logLevel.intValue()){
            if (level.intValue() >= Level.WARNING.intValue()){
                doWriteErr(dolog(level, s));
            } else {
                doWriteOut(dolog(level, s));
            }
        }
    }

    static void logStd (Level level, String s){
        if (level.intValue() >= logLevel.intValue()){
            if (level.intValue() >= Level.WARNING.intValue()){
                System.err.println(dolog(level, s));
            } else {
                System.out.println(dolog(level, s));
            }
        }
    }
    
    static void log (Level level, String s, Exception e){
        
        if (level.intValue() >= logLevel.intValue()){
            doWriteErr(dolog(level, s, e));
            if (logLevel.intValue() <= Level.FINE.intValue()){
                for (StackTraceElement ste : e.getStackTrace()){
                    doWriteErr(String.format("         %s%n", ste.toString()));
                }
            }
        }
    }
    
    static void setLogFile (File file) throws IOException {
        if (file != null && !"-".equals(file.getName())) {
            log (Level.FINEST, "Opening log file for writing: " + file.getCanonicalPath());
            logWriter = new BufferedWriter(new FileWriter(file, true /*append*/));
            logWriter.write("==========  Opened log file " + DATE_TIME_FORMAT.get().format(System.currentTimeMillis()) + " ==========\n");
            logWriter.flush();
        }
    }
    
    static void closeLogFile () throws IOException {
        if (logWriter != null) {
            logWriter.write("..........  Closed log file " + DATE_TIME_FORMAT.get().format(System.currentTimeMillis()) + " ..........\n");
            logWriter.close();
        }
    }
    
    static void setLogLevel (Level newLevel){
        logLevel = newLevel;
    }
    
    static String getLevel(){
        return logLevel.toString();
    }

}
