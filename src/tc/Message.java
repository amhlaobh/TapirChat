package tc;

import java.util.logging.Level;
import java.util.UUID;

/** 
 * Encapsulates a chat message.
 * Messages are five-part Strings, separated by a "|" {@see FS}.
 * Parts:
 * <ul>
 * <li>username, String
 * <li>message type {@see MessageType}, String
 * <li>timestamp, long milliseconds
 * <li>message id, unique String
 * <li>message body, String
 * </ul>
 * @author ok
 *
 */
public class Message {
    
    public enum MessageType {
        USERMSG, ARCHIVEDMSG, HEARTBEAT, ARCHIVE, SHUTDOWN, WHOSONLINE, VERSION, CONNECT, ISTYPING
    }
    
    static final char FS = '|';
    
    String userName;
    String messageBody;
    MessageType messageType;
    String messageId;
    long timeStamp = -1L;
    boolean acked;
    
    /* TODO: make even better id; collisions are still possible, e.g.
    [FINEST |RcveThread|23.02 14:40:35.763]:   -- Parsing 'loadui 2388|USERMSG|1298472035762|7533142544|him,) 'I'll give him sixpence. _I_ don't believe there's a
n atom of' 
[FINE   |SendThread|24.02 09:39:50.798]: Sending message from queue: 'loadui 9966|USERMSG|1298540371633|7533142544|Rabbit returning, splendidly dressed, with a pair of white kid gloves in'
*/
    private static String createMessageId(String userName) {
        // totally watertight would be to use the full UUID, but just the last part should be enough for this application
        // but needs type of id changed to String
        //String uid = userName + "-" + UUID.randomUUID().toString().split("-")[4];
        String uid = UUID.randomUUID().toString().split("-")[4];

        return uid;
    }

    public Message (String userName, MessageType messageType, String messageBody){
        this(userName, messageType, System.currentTimeMillis(), messageBody, createMessageId(userName));
    }

    public Message (String userName, MessageType messageType, String messageBody, String messageId){
        this(userName, messageType, System.currentTimeMillis(), messageBody, messageId);
    }

    public Message (String userName, MessageType messageType, long timeReceived, 
            String messageBody, String messageId){
        this.userName = userName;
        this.messageType = messageType;
        this.messageBody = messageBody;
        this.timeStamp = timeReceived;
        this.messageId = messageId;
    }
    
    public Message (String structuredString) throws MessageException {
        Logger.log (Level.FINEST, "  -- Parsing '"+structuredString+"'");
        String[] parts = structuredString.split("\\"+FS);
        if (parts.length != 5) {
            Logger.log (Level.FINE, String.format("StructuredString [%d parts]:'%s'",parts.length, structuredString));
            throw new MessageException ("Expected exactly 5 parts to message string.");
        }
            
        userName = parts[0];
        try {
            messageType = MessageType.valueOf(parts[1]);
        } catch (IllegalArgumentException iae){
            throw new MessageException("Unknown message type: "+ parts[1]);
        }
        try {
            timeStamp = Long.parseLong(parts[2]);
        } catch (NumberFormatException nfe) {
            throw new MessageException("Not a number: "+ parts[2]);
        }
        if (timeStamp < 0){
            throw new MessageException("Negative timestamp: "+ parts[2]);
        }
        messageId = parts[3];
        messageBody = parts[4];
    }
    
    /**
     * Create an identical copy of this Message which, if subsequently modified,
     * does not modify the original because its fields have different pointers
     * to different objects.
     * 
     * @param msg
     * @return an identical copy/clone of the original
     */
    public static Message createCopy (Message msg) {
        return (new Message (msg.userName, msg.messageType, msg.timeStamp, 
                msg.messageBody, msg.messageId));
    }
    
    /** Quick way to create ACK messages: all fields are the same, except the body. */
    public static Message createAckMessage (Message msg) {
        return (new Message (msg.userName, msg.messageType, msg.timeStamp, "ACK", msg.messageId));
    }
    
    public static boolean verifyAck (Message o, Message a) {
        return (o != null) && (a != null) && 
            (o.messageId.equals(a.messageId)) &&
            (o.timeStamp == a.timeStamp) &&
            (o.userName.equals(a.userName)) &&
            (o.messageType == a.messageType) &&
            "ACK".equals(a.messageBody);
    }
    
    public String toStructuredString (){
        StringBuilder sb = new StringBuilder();
        sb.append(userName).append(FS);
        sb.append(messageType.name()).append(FS);
        sb.append(timeStamp).append(FS);
        sb.append(messageId).append(FS);
        sb.append(messageBody);
        
        return sb.toString();
    }
    
    static class MessageException extends Exception {
        public MessageException (String message) {
            super(message);
        }
        
        public MessageException (String message, Throwable cause){
            super(message, cause);
        }
    }
    
}
