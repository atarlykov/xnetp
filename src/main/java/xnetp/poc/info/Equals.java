package xnetp.poc.info;

import java.util.Objects;

public class Equals {


    public static class A {
        int x, y;

//        @Override
//        public boolean equals(Object o) {
//            if (this == o) return true;
//            if (o == null || getClass() != o.getClass()) return false;
//            A a = (A) o;
//            return x == a.x &&
//                    y == a.y;
//        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof A)) return false;
            A a = (A) o;
            return x == a.x &&
                    y == a.y;
        }

        @Override
        public int hashCode() {

            return Objects.hash(x, y);
        }
    }

    public static class B extends  A{
        int z;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof B)) return false;
            if (!super.equals(o)) return false;
            B b = (B) o;
            return z == b.z;
        }


        @Override
        public int hashCode() {

            return Objects.hash(super.hashCode(), z);
        }
    }

    public static void main(String[] args) {
        A a = new A();
        a.x = 1; a.y = 2;

        B b = new B();
        b.x = 1; b.y = 2; b.z = 3;

        System.out.println(a.equals(b));
        System.out.println(b.equals(a));

/*
        long tStart = System.currentTimeMillis();
        StringBuilder xb = new StringBuilder();
        String x = "";
        for (int i = 0 ; i < 1_000_000; i++) {
//            x += "#data:";
//            x += i;
            xb.append("#data:");
            xb.append(i);
        }
        long tEnd = System.currentTimeMillis();
        System.out.println(xb.charAt(0));
        System.out.println((tEnd - tStart));
*/
    }
}
