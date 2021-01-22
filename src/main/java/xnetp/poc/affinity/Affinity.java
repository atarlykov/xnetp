package xnetp.poc.affinity;

import java.util.BitSet;

/**
 * Simple Facade to assign thread affinity
 */
public class Affinity {

    /*
     * library loading and necessary optimizations
     */
    static {
        System.loadLibrary("scheda");
    }

    /**
     * sets affinity of the current thread to only one cpu
     * @param cpu cpu index [0...]
     */
    public static void setAffinity(int cpu) {
        int idxSlot = cpu >> 3;
        byte idxBit = (byte)(0x01 << (cpu & 0x7));

        byte[] mask = new byte[1 + idxSlot];
        mask[idxSlot] = idxBit;
        __setAffinity(mask);
    }


    /**
     * sets affinity of the current thread to all required cpu
     * @param mask cpu bit mask
     */
    public static void setAffinity(BitSet mask) {
        __setAffinity(mask.toByteArray());
    }

    /**
     * sets affinity of the current thread to all required cpu
     * @param mask cpu bit mask
     */
    public static void setAffinity(byte[] mask) {
        __setAffinity(mask);
    }

    /**
     * gets affinity of the current thread
     */
    public static BitSet getAffinity() {
        byte[] bytes = __getAffinity();
        return BitSet.valueOf(bytes);
    }

    /**
     * @return affinity mask for current thread,
     * null if mask in unavailable
     */
    native static byte[] __getAffinity();

    /**
     * @param affinity affinity mask to be set for the current thread
     */
    native static void __setAffinity(byte[] affinity);

    /**
     * @return thread id of the current thread or -1 if it's not available
     */
    native static int __getThreadId();
}