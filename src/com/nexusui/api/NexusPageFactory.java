package com.nexusui.api;

/**
 * Factory for creating NexusPage instances.
 *
 * Register via NexusFrame.registerPageFactory() to enable multi-window support.
 * Each NexusFrame window will call create() to get its own independent page instance,
 * so pages with internal UI state (panel references, timers) work correctly
 * across multiple simultaneous windows.
 */
public interface NexusPageFactory {

    /** Unique identifier matching the page's getId(). */
    String getId();

    /** Display title matching the page's getTitle(). */
    String getTitle();

    /** Create a fresh NexusPage instance for a new window. */
    NexusPage create();
}
