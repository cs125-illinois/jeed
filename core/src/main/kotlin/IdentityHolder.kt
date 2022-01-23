package edu.illinois.cs.cs125.jeed.core

class IdentityHolder(val item: Any) {
    override fun equals(other: Any?): Boolean {
        return other is IdentityHolder && other.item === item
    }

    override fun hashCode(): Int {
        return System.identityHashCode(item)
    }
}
