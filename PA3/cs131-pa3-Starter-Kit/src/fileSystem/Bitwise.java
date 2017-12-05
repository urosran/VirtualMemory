package fileSystem;

/**
 * Manage bitwise operations in a byte or array of bytes.
 *
 * Unit tests are in {@see TestBitwise}. See TestBitwise.java.
 */
public class Bitwise {
	
    private static final int bitmasks[] = {1, 2, 4, 8, 16, 32, 64, 128};
    	
    /**
     * Check to see if bit i is set in byte. Returns true if it is
     * set, false otherwise.
     */
    public static boolean isset(int i, byte b) {//FIXME!!!
    	return ((byte) b & bitmasks[i])>0;
    //& operator check if the bit inside of the bite is set to 1 	
    	
    }

    /**
     * Check to see if bit i is set in array of bytes. Returns true if
     * it is set, false otherwise.
     */
    public static boolean isset(int i, byte bytes[]) {//FIXME!!!
    	//getting an array of bytes and getting each one out 
    	int byteIndex = bytes.length-1-i/8;
    	return isset(i%8, bytes[byteIndex]);
    	
//    	int byteIndex = i/8;
//    	return ((byte) bytes[byteIndex]&bitmasks[i%8]) > 0;
    }

    /**
     * Set bit i in byte and return the new byte.
     */
    public static byte set(int i, byte b) {//FIXME!!!
    	return (byte) (b|bitmasks[i]);
    	//you use || to set the byte - turns 0 and 0 to 1
    }

    /**
     * Set bit i in array of bytes.
     */
    public static void set(int i, byte bytes[]) {//FIXME!!!
    	//choose the right array from the array of bites - if the i is 32 beacaue you wanna get the 4th position
    	int intIndex = bytes.length-1-i/8;    	
    	bytes[intIndex] = set(i%8, bytes[intIndex]);
    }

    /**
     * Clear bit i in byte and return the new byte.
     */
    public static byte clear(int i, byte b) {//FIXME!!!
    	//bitwise or - XOR 
    	return (byte) (b&~bitmasks[i%8]);//he pases just the regular i
    }

    /**
     * Clear bit i in array of bytes and return true if the bit was 1
     * before clearing, false otherwise.
     */
    public static boolean clear(int i, byte bytes[]) {//FIXME!!!
    	int intIndex = bytes.length-1-i/8;
    	
    	boolean returnVal = isset(i%8, bytes[intIndex]);
    	bytes[intIndex] = clear(i%8,bytes[intIndex]);
    	
    	return returnVal;
    	
    }

    /**
     * Clear every bit in array of bytes.
     *
     * There is no clearAll for a single byte, you can just get a new
     * byte for that.
     */
    public static void clearAll(byte bytes[]) {
        for(int i = 0; i < bytes.length; ++i) {
            bytes[i] = 0;
        }
    }

    /**
     * Convert byte to a string of bits. Each bit is represented as
     * "0" if it is clear, "1" if it is set.
     */
    public static String toString(byte b) {//FIXME!!!
    	//for every 8 spaces perform the toBinaryString where we change each space provided by the 
    	// & operator to be a 0 instead of an empty space
    	return String.format("%8s", Integer.toBinaryString(b&0xFF)).replace(' ','0');
    }

    /**
     * Convert array of bytes to string of bits (each byte converted
     * to a string by calling {@link #byteToString(byte b)}, every
     * byte separated by sep, every "every" bytes separated by lsep.
     */
    public static String toString(byte bytes[], String sep,
                                  String lsep, int every) {
        String s = "";
        for(int i = bytes.length * 8 - 1; i >= 0; --i) {
        	s += isset(i, bytes) ? "1" : "0";
        	if(i > 0)
                if(every > 0 && i % (8 * every) == 0)
                    s += lsep;
                else if(i % 8 == 0)
                    s += sep;
        }
        return s;
    }

    /**
     * Convert array of bytes to string of bits, each byte separated
     * by sep. See {@link #byteToString(byte bytes[], String sep)}.
     */
    public static String toString(byte bytes[], String sep) {
        return toString(bytes, sep, null, 0);
    }

    /**
     * Convert array of bytes to string of bits, each byte separated
     * by a comma, and every 8 bytes separated by a newline. See
     * {@link #byteToString(byte bytes[], String sep)}.
     */
    public static String toString(byte bytes[]) {
        return toString(bytes, ",", "\n", 8);
    }
}