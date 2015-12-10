package tc;

import java.util.logging.Level;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.DESKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;


public class ObjectCrypter {

private Cipher deCipher;
private Cipher enCipher;
private SecretKeySpec key;
private IvParameterSpec ivSpec;

/** 
 * From: http://stackoverflow.com/questions/1205135/how-to-encrypt-string-in-java
 * Example:
 *
        ObjectCrypter oe = new ObjectCrypter(
        new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}, 
        new byte[]{0x05, 0x06, 0x07, 0x08, 0x09, 0x04, 0x03, 0x01});

        byte[] b = oe.encrypt("In einem Loch in der Erde, da lebte ein Hobbit ö ß ? £ ");
        System.out.println(oe.decrypt(b));
*/

public ObjectCrypter(byte[] keyBytes,   byte[] ivBytes) {
    // wrap key data in Key/IV specs to pass to cipher


     ivSpec = new IvParameterSpec(ivBytes);
    // create the cipher with the algorithm you choose
    // see javadoc for Cipher class for more info, e.g.
    try {
         DESKeySpec dkey = new  DESKeySpec(keyBytes);
          key = new SecretKeySpec(dkey.getKey(), "DES");
         deCipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
         enCipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
    } catch (NoSuchAlgorithmException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
    } catch (NoSuchPaddingException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
    } catch (InvalidKeyException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
    }
}

/* Returns encrypted object or empty array if obj is null.
 */
public byte[] encrypt(Object obj) throws InvalidKeyException, InvalidAlgorithmParameterException, IOException, IllegalBlockSizeException, ShortBufferException, BadPaddingException {
    byte[] input = convertToByteArray(obj);
    enCipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);

    if (obj == null){
        return new byte[0];
    }

    return enCipher.doFinal(input);




//  cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
//  byte[] encypted = new byte[cipher.getOutputSize(input.length)];
//  int enc_len = cipher.update(input, 0, input.length, encypted, 0);
//  enc_len += cipher.doFinal(encypted, enc_len);
//  return encypted;


}


/** Returns encrypted object or null, if encrypted was null or the empty array.
 */
public Object decrypt( byte[]  encrypted) throws InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, IOException, ClassNotFoundException {
    if (encrypted == null  || encrypted.length == 0){
        return null;
    }
    deCipher.init(Cipher.DECRYPT_MODE, key, ivSpec);

    return convertFromByteArray(deCipher.doFinal(encrypted));

}



public String encryptString (String s) {
    StringBuilder sb = new StringBuilder();
    byte[] b = null;
    try {
        b = encrypt(s);
    } catch (Exception e){
        Logger.log(Level.WARNING, "Encryption error ", e);
        return "";
    }
    for (int i=0; i<b.length; i++){
        //sb.append(String.valueOf(b[i])).append(" ");
        sb.append(String.format("%x", b[i])).append(" ");
    }
    return sb.toString();
}

public String decryptString (String s)  {
    String[] sa = s.split(" ");
    byte[] b = new byte[sa.length];
    for (int i=0; i<b.length; i++){
        //System.out.print(sa[i]+" ");
        try {
            b[i] = (byte)Integer.parseInt(sa[i], 16);
        } catch (NumberFormatException pe){
            Logger.log(Level.WARNING, "Can't parse integer "+ sa[i], pe);
        }
    }
    Object rv = null;
    try {
        rv = decrypt(b);
    } catch (Exception e){
        Logger.log(Level.WARNING, "Decryption error ", e);
        return "";
    }
    return (String)rv;
}

private Object convertFromByteArray(byte[] byteObject) throws IOException,
        ClassNotFoundException {
    ByteArrayInputStream bais;

    ObjectInputStream in;
    bais = new ByteArrayInputStream(byteObject);
    in = new ObjectInputStream(bais);
    Object o = in.readObject();
    in.close();
    return o;

}



private byte[] convertToByteArray(Object complexObject) throws IOException {
    ByteArrayOutputStream baos;

    ObjectOutputStream out;

    baos = new ByteArrayOutputStream();

    out = new ObjectOutputStream(baos);

    out.writeObject(complexObject);

    out.close();

    return baos.toByteArray();

}


}
