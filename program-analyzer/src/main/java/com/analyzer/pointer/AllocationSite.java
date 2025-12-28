package com.analyzer.pointer;
public class AllocationSite {
    public final String id;

    public AllocationSite(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AllocationSite)) return false;
        return id.equals(((AllocationSite) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
