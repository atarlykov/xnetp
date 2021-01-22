package xnetp.poc.info;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DBufferTest {

    private static final VarHandle MODIFIERS;
    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Field.class, MethodHandles.lookup());
            MODIFIERS = lookup.findVarHandle(Field.class, "modifiers", int.class);
        } catch (IllegalAccessException | NoSuchFieldException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void makeNonPrivate(Field field) {
        int mods = field.getModifiers();
        if (Modifier.isPrivate(mods)) {
            MODIFIERS.set(field, mods & ~Modifier.PRIVATE);
        }
    }


    static ByteBuffer b1 = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
    static ByteBuffer b2;
    static {
        b2 = ByteBuffer.allocateDirect(0);
    }

//    static final VarHandle BB_address;
//    static final VarHandle BB_capacity;
    static {
        try {
//            BB_address = MethodHandles.privateLookupIn(ByteBuffer.class, MethodHandles.lookup()).
//                    findVarHandle(ByteBuffer.class, "address", long.class);
//            BB_capacity = MethodHandles.privateLookupIn(ByteBuffer.class, MethodHandles.lookup()).
//                    findVarHandle(ByteBuffer.class, "capacity", int.class);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public static void main(String[] args) throws Exception {


        Cleaner c = Cleaner.create();
        //c.register()


        //System.out.println(BB_address.get(b1));
        //System.out.println(BB_capacity.get(b1));

        Field fAddress = Buffer.class.getDeclaredField("address");
        fAddress.setAccessible(true);

        Field fCapacity = Buffer.class.getDeclaredField("capacity");
        makeNonPrivate(fCapacity);
        fCapacity.setAccessible(true);

        Field fLimit = Buffer.class.getDeclaredField("limit");
        makeNonPrivate(fLimit);
        fLimit.setAccessible(true);

        // dump b1
        System.out.println("b1:");
        System.out.println(fAddress.get(b1));
        System.out.println(fCapacity.get(b1));
        System.out.println(fLimit.get(b1));

        // remember
        long b2address = (long)fAddress.get(b2);
        int b2capacity = (int)fCapacity.get(b2);
        int b2limit = (int)fLimit.get(b2);

        // dump b2
        System.out.println("b2:");
        System.out.println(b2address);
        System.out.println(b2capacity);
        System.out.println(b2limit);


        // change b2
        fAddress.set(b2, fAddress.get(b1));
        fCapacity.set(b2, fCapacity.get(b1));
        fLimit.set(b2, fLimit.get(b1));
        // and dump
        System.out.println("b2:");
        System.out.println(fAddress.get(b2));
        System.out.println(fCapacity.get(b2));


        // check that both buffers share the same memory space
        b1.put(0, (byte)11);
        System.out.println("b1 read: " + b1.get(0));
        System.out.println("b2 read: " + b2.get(0));

        // restore
        fAddress.set(b2, b2address);
        fCapacity.set(b2, b2capacity);
        fLimit.set(b2, b2limit);
    }
}
