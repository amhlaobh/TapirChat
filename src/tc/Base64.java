package tc;

import java.io.ByteArrayOutputStream;

public final class Base64
{
  static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";


  // hidden utility class constructor
  private Base64(){
  }

  public static byte[] decode(String inStr)
  {
    StringBuffer tmp = new StringBuffer(inStr);
    int idx;
    while ((idx = tmp.indexOf("\n")) != -1) {
      tmp.deleteCharAt(idx);
    }
    String in = tmp.toString();

    ByteArrayOutputStream bos = new ByteArrayOutputStream();

    byte[] buf = new byte[3];
    int length = 3;

    for (int i = 0; i < in.length(); i += 4) {
      int a = ALPHABET.indexOf(in.charAt(i));
      int b = ALPHABET.indexOf(in.charAt(i + 1));
      int c = ALPHABET.indexOf(in.charAt(i + 2));
      int d = ALPHABET.indexOf(in.charAt(i + 3));

      buf[0] = (byte)(a << 2 | b >>> 4);
      buf[1] = (byte)(b << 4 | c >>> 2);
      buf[2] = (byte)(c << 6 | d);

      if (d == -1) {
        length--;
      }
      if (c == -1) {
        length--;
      }
      bos.write(buf, 0, length);
    }

    return bos.toByteArray();
  }

  public static String encode(byte[] in) {
    return encode(in, false);
  }

  public static String encode(byte[] in, boolean linebreaks) {
    StringBuffer out = new StringBuffer();

    byte[] buf = new byte[3];

    int j = 0;
    for (int i = 0; i < in.length; i += 3) {
      buf[0] = in[i];
      buf[1] = (i + 1 < in.length ? in[(i + 1)] : 0);
      buf[2] = (i + 2 < in.length ? in[(i + 2)] : 0);

      int a = buf[0] >>> 2;
      int b = buf[0] << 4 & 0x3F | buf[1] >>> 4;
      int c = buf[1] << 2 & 0x3F | buf[2] >>> 6;
      int d = buf[2] & 0x3F;

      if ((linebreaks) && (j % 76 == 0)) {
        out.append("\n");
      }
      out.append("" + ALPHABET.charAt(a) + 
              ALPHABET.charAt(b) + 
              ALPHABET.charAt(c) + 
              ALPHABET.charAt(d));
      j += 4;
    }

    int padding = in.length % 3;
    if (padding != 0) {
      padding = 3 - padding;
    }
    for (int i = 0; i < padding; i++) {
      out.setCharAt(out.length() - 1 - i, '=');
    }

    return out.toString();
  }

  public static void main(String[] args) {
    String tst = "To the batmobile! Atomic batteries to power. Turbines to speed.";

    System.out.println("Encoded: " + encode(tst.getBytes(), false));
    System.out.println("Encoded: " + encode(tst.getBytes(), true));
    System.out.println("Decoded: " + new String(decode(encode(tst.getBytes(), false))));
  }
}
