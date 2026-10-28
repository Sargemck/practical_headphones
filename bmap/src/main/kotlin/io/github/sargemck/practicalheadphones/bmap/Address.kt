package io.github.sargemck.practicalheadphones.bmap

/** A BMAP function address, `fblock.func`, e.g. `31.10`. */
data class Address(val fblock: Int, val func: Int) {
    override fun toString(): String = "$fblock.$func"
}
