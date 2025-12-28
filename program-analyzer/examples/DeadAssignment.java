package examples;

public class DeadAssignment {
    public static void main(String[] args) {
        int x = 5;   // DEAD
        int y = 10;

        System.out.println(y);
    }
}
