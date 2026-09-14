package vn.railticketing.inventory.exception;

public class InsufficientInventoryException extends RuntimeException {

    private final int requested;
    private final int available;

    public InsufficientInventoryException(int requested, int available) {
        super("Requested " + requested + " berths, only " + available + " free");
        this.requested = requested;
        this.available = available;
    }

    public int getRequested() { return requested; }
    public int getAvailable() { return available; }
}
