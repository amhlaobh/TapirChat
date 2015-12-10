package tc;

import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.net.URL;
import java.util.logging.Level;

import javax.swing.ImageIcon;

/**
 * Display bubbles/popups and blinking alert icons in the system tray. Use the
 * {@link #setAlert()}, {@link #setAlert(String)},
 * {@link #setAlert(String, boolean) and {@link #resetAlert()} methods.
 * 
 * Clicking the balloon or icon brings the registered calling UI to front.
 * 
 * If no graphic environment is detected (i.e. problems with DISPLAY variable,
 * can't load images, headless mode or other problems), just do nothing.
 * 
 * @author ok
 * 
 */
public class Tray {
    TrayIcon trayIcon = null;
    private static Image imageEmpty;
    private static Image imageTapir;
    private static Image imageBlink;
    private static Image lastImage;
    private static boolean trayAvailable = SystemTray.isSupported();
    private BlinkingTray blinkingTray; 
    private TextUI callingUI;
    
    static {
        try {
            if (!GraphicsEnvironment.isHeadless()){
                //NPE might be thrown by sun.awt.image.URLImageSource.getConnection if image not present in jar file
                URL imgURL = ClassLoader.getSystemClassLoader().getClass().getResource("/img/tray.png");
                imageTapir = new ImageIcon(imgURL, "This is our icon").getImage();
                if (imageTapir != null)
                    Logger.log(Level.FINER, "Loading tray icon from /img/tray.png");
                imgURL = ClassLoader.getSystemClassLoader().getClass().getResource("/img/tray_green.png");
                imageBlink = new ImageIcon(imgURL, "This is our icon").getImage();
                if (imageBlink != null)
                    Logger.log(Level.FINER, "Loading tray icon from /img/tray_green.png");
                imgURL = ClassLoader.getSystemClassLoader().getClass().getResource("/img/tray_transparent.png");
                imageEmpty = new ImageIcon(imgURL, "This is our icon").getImage();
                if (imageEmpty != null)
                    Logger.log(Level.FINER, "Loading tray icon from /img/tray_transparent.png");
            }
        } catch (NullPointerException npe) { 
            Logger.log(Level.SEVERE, "Problem loading tray images", npe);
            trayAvailable = false;
        } catch (Exception e1) {
            Logger.log(Level.SEVERE, "Problem loading tray images", e1);
            trayAvailable = false;
            // sollte nicht noetig sein, dann ist einfach kein tray icon da (26.7.2011)
            //System.exit(1);
        } catch (InternalError ie) {
            // most likely to do with DISPLAY not available
            Logger.log(Level.WARNING, "InternalError. Try to call java with -Djava.awt.headless=true" + 
                    " or ignore, can't load tray images. ");
            Logger.log(Level.WARNING, ie.getMessage());
            trayAvailable = false;
        }
    }

    public Tray(){
        this(null);
    }
    
    public Tray(TextUI callingUI){
        this.callingUI = callingUI;
        boolean headlessCheck = GraphicsEnvironment.isHeadless();
        if (headlessCheck || !trayAvailable){
            Logger.log(Level.WARNING, "Running headless, no system tray available.");
            return;
        }


        PopupMenu popup = new PopupMenu();
        
        SystemTray tray = SystemTray.getSystemTray();

        MouseListener mouseListener = new MouseListener() {
                    
            public void mouseClicked(MouseEvent e) {
                if (Tray.this.callingUI != null){
                    resetAlert();
                    Tray.this.callingUI.toFront();
                }
            }

            public void mouseEntered(MouseEvent e) {
                //System.out.println("Tray Icon - Mouse entered!");                 
            }

            public void mouseExited(MouseEvent e) {
                //System.out.println("Tray Icon - Mouse exited!");                 
            }

            public void mousePressed(MouseEvent e) {
                //System.out.println("Tray Icon - Mouse pressed!");                 
            }

            public void mouseReleased(MouseEvent e) {
                //System.out.println("Tray Icon - Mouse released!");                 
            }
        };

        ActionListener resetIconListener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                resetAlert();
            }
        };

        // ignore findbugs Method invokes System.exit(...)
        ActionListener exitListener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Logger.log(Level.INFO, "Exiting...");
                System.exit(0);
            }
        };

        MenuItem defaultItem = new MenuItem("Reset Icon");
        defaultItem.addActionListener(resetIconListener);
        popup.add(defaultItem);
        MenuItem exitItem = new MenuItem ("Exit program");
        exitItem.addActionListener(exitListener);
        popup.add(exitItem);

        trayIcon = new TrayIcon(imageEmpty, "Neue Nachricht", popup);

        ActionListener actionListener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // Fenster nach oben holen? 
                if (Tray.this.callingUI != null){
                    resetAlert();
                    Tray.this.callingUI.toFront();
                }
                resetAlert();
            }
        };
                
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(actionListener);
        trayIcon.addMouseListener(mouseListener);

        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            System.err.println("TrayIcon could not be added.");
        }

        setOffline();

    }
    
    /**
     * Make tray icon blink.
     * 
     * @param toolTipString
     */
    synchronized void setAlert(){
        if (trayAvailable){
            blinkingTray = new BlinkingTray(this);
            blinkingTray.start();
        }
    }
    
    /**
     * Set the alert text.
     * 
     * @param toolTipString
     */
    synchronized void setAlert (final String toolTipString){
        Runnable runInEventDispatchThread = new Runnable() {  
            public void run() {     
                setAlert(toolTipString, true);
            }
        };
        SwingUtilities.invokeLater(runInEventDispatchThread);  
    }

    /**
     * Make tray icon blink, set the alert text and show a balloon if second
     * parameter is true, else don't show balloon.
     * 
     * @param toolTipString
     * @param showBalloon
     */
    synchronized void setAlert(final String toolTipString, boolean showBalloon){
        if (trayAvailable){
            setAlert();
            Runnable runInEventDispatchThread = new Runnable() {  
                public void run() {     
                    trayIcon.setToolTip(toolTipString);
                }
            };
            SwingUtilities.invokeLater(runInEventDispatchThread);  
            Logger.log(Level.FINEST, "setAlert("+toolTipString+")");
            if (showBalloon){
                trayIcon.displayMessage("Tapirchat", toolTipString, MessageType.NONE);
            }
        }
    }
    
    synchronized void resetAlert(){
        if (trayAvailable){
            blinkingTray = null;
        }
    }
    
    private BlinkingTray getBlinkingTray(){
        return blinkingTray;
    }
    
    synchronized void setOnline(){
        if (!trayAvailable) return;
        Runnable runInEventDispatchThread = new Runnable() {  
            public void run() {     
                trayIcon.setImage(imageTapir);
            }
        };
        SwingUtilities.invokeLater(runInEventDispatchThread);  
        lastImage = imageTapir;
    }
    
    // final because called from constructor
    synchronized final void setOffline(){
        if (!trayAvailable) return;
        Runnable runInEventDispatchThread = new Runnable() {  
            public void run() {     
                trayIcon.setImage(imageEmpty);
            }
        };
        SwingUtilities.invokeLater(runInEventDispatchThread);  
        lastImage = imageEmpty;
    }
    
    synchronized void setLastImage(){
        Runnable runInEventDispatchThread = new Runnable() {  
            public void run() {     
                trayIcon.setImage (lastImage);
            }
        };
        SwingUtilities.invokeLater(runInEventDispatchThread);  
    }
    
    static class BlinkingTray extends Thread {
        Tray tray;
        public BlinkingTray (Tray tray){
            this.tray = tray;
        }
        
        public void run(){
            Thread thisThread = Thread.currentThread();
            while (tray.getBlinkingTray() == thisThread) {
                tray.trayIcon.setImage(imageTapir);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    //
                }
                if (tray.getBlinkingTray() == thisThread){
                    tray.trayIcon.setImage(imageBlink);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        //
                    }
                }
            }
            tray.setLastImage();
        }
    }

    public static void main(String[] args){
        Tray tray = new Tray(null);
        try {Thread.sleep (2000);} catch (InterruptedException e){}
        tray.setAlert();
        try {Thread.sleep (2000);} catch (InterruptedException e){}
        tray.resetAlert();
    }
}
