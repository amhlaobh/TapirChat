package tc;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.net.URL;

import javax.swing.ImageIcon;


public class ThreadMemTest extends Thread {

    private volatile boolean running = true;
    private static Image myimg;
    private static TrayIcon trayIcon = null;
    private static SystemTray tray;
    
    public ThreadMemTest() throws Exception {
        URL imgURL = ClassLoader.getSystemClassLoader().getClass().getResource("/img/tray.png");
        myimg = new ImageIcon(imgURL, "This is our icon").getImage();
        trayIcon = new TrayIcon(myimg, "Some news", null);
        trayIcon.setImageAutoSize(true);
        tray = SystemTray.getSystemTray();
        tray.add(trayIcon);
    }

    public void run (){
        while (running) {
            tray.remove(trayIcon);
            trayIcon = new TrayIcon(myimg, "Some news", null);
            trayIcon.setImageAutoSize(true);
            tray = SystemTray.getSystemTray();
            try {
                tray.add(trayIcon);
            } catch (AWTException a){}
            
            //{trayIcon.setImage(myimg);}
        }
        System.out.println("Thread ends");
    }
    
    public void requestStop(){
        running = false;
        System.out.println("Request stop");
    }
    
    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        //Thread.sleep (10000);
        //System.out.println("start");
        ThreadMemTest t = new ThreadMemTest();
        t.setDaemon(true);
        t.start();
        
        Thread.sleep (600000);
        t.requestStop();

        System.gc();
        System.out.println("stopped");
        Thread.sleep (60000);
        System.exit(0);

    }

}
