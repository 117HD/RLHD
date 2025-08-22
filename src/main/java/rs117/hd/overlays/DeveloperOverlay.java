package rs117.hd.overlays;

/**
 * Interface for objects that can be activated and deactivated.
 * This provides a common contract for overlay activation behavior.
 */
public interface DeveloperOverlay {
    
    /**
     * Activates the object.
     * @throws Exception if activation fails
     */
    void activate() throws Exception;
    
    /**
     * Deactivates the object.
     * @throws Exception if deactivation fails
     */
    void deactivate() throws Exception;
    
    /**
     * Sets the active state of the object.
     * This is a convenience method that calls either activate() or deactivate().
     * @param active true to activate, false to deactivate
     * @throws Exception if the state change fails
     */
    default void setActive(boolean active) throws Exception {
        if (active) {
            activate();
        } else {
            deactivate();
        }
    }
}
