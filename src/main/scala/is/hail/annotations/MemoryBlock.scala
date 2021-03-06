package is.hail.annotations

import java.util

import is.hail.expr._
import is.hail.utils._
import is.hail.variant.{AltAllele, Genotype, Locus, Variant}
import org.apache.spark.sql.Row
import org.apache.spark.unsafe.Platform

import scala.collection.mutable

object MemoryBuffer {
  def apply(sizeHint: Int = 128): MemoryBuffer = {
    new MemoryBuffer(new Array[Byte](sizeHint))
  }
}

final class MemoryBuffer(var mem: Array[Byte], var offset: Int = 0) {
  def size: Int = offset

  def copyFrom(other: MemoryBuffer, readStart: Int, writeStart: Int, n: Int) {
    assert(size <= mem.length)
    assert(other.size <= other.mem.length)
    assert(n >= 0)
    assert(readStart >= 0 && readStart + n <= other.size)
    assert(writeStart >= 0 && writeStart + n <= size)
    Platform.copyMemory(other.mem, readStart + Platform.BYTE_ARRAY_OFFSET, mem,
      writeStart + Platform.BYTE_ARRAY_OFFSET, n)
  }

  def loadInt(off: Int): Int = {
    assert(size <= mem.length)
    assert(off >= 0 && off + 4 <= size, s"tried to read int at $off from region with size $size")
    Platform.getInt(mem, Platform.BYTE_ARRAY_OFFSET + off)
  }

  def loadLong(off: Int): Long = {
    assert(size <= mem.length)
    assert(off >= 0 && off + 8 <= size, s"tried to read long at $off from region with size $size")
    Platform.getLong(mem, Platform.BYTE_ARRAY_OFFSET + off)
  }

  def loadFloat(off: Int): Float = {
    assert(size <= mem.length)
    assert(off >= 0 && off + 4 <= size, s"tried to read float at $off from region with size $size")
    Platform.getFloat(mem, Platform.BYTE_ARRAY_OFFSET + off)
  }

  def loadDouble(off: Int): Double = {
    assert(size <= mem.length)
    assert(off >= 0 && off + 8 <= size, s"tried to read double at $off from region with size $size")
    Platform.getDouble(mem, Platform.BYTE_ARRAY_OFFSET + off)
  }

  def loadByte(off: Int): Byte = {
    assert(size <= mem.length)
    assert(off >= 0 && off + 1 <= size, s"tried to read byte at $off from region of size $size")
    Platform.getByte(mem, Platform.BYTE_ARRAY_OFFSET + off)
  }

  def loadBytes(off: Int, n: Int): Array[Byte] = {
    assert(size <= mem.length)
    assert(off >= 0 && off + n <= size, s"tried to read bytes of size $n at $off from region of size $size")
    val a = new Array[Byte](n)
    Platform.copyMemory(mem, Platform.BYTE_ARRAY_OFFSET + off, a, Platform.BYTE_ARRAY_OFFSET, n)
    a
  }

  def loadBytes(off: Int, n: Int, dst: Array[Byte]) {
    assert(size <= mem.length)
    assert(off >= 0 && off + n <= size, s"tried to read bytes of size $n at $off from region of size $size")
    assert(n <= dst.length)
    Platform.copyMemory(mem, Platform.BYTE_ARRAY_OFFSET + off, dst, Platform.BYTE_ARRAY_OFFSET, n)
  }

  def storeInt(off: Int, i: Int) {
    assert(size <= mem.length)
    assert(off >= 0 && off + 4 <= size, s"tried to store int at $off to region of size $size")
    Platform.putInt(mem, Platform.BYTE_ARRAY_OFFSET + off, i)
  }

  def storeLong(off: Int, l: Long) {
    assert(size <= mem.length)
    assert(off >= 0 && off + 8 <= size, s"tried to store long at $off to region of size $size")
    Platform.putLong(mem, Platform.BYTE_ARRAY_OFFSET + off, l)
  }

  def storeFloat(off: Int, f: Float) {
    assert(size <= mem.length)
    assert(off >= 0 && off + 4 <= size, s"tried to store float at $off to region of size $size")
    Platform.putFloat(mem, Platform.BYTE_ARRAY_OFFSET + off, f)
  }

  def storeDouble(off: Int, d: Double) {
    assert(size <= mem.length)
    assert(off >= 0 && off + 8 <= size, s"tried to store double at $off to region of size $size")
    Platform.putDouble(mem, Platform.BYTE_ARRAY_OFFSET + off, d)
  }

  def storeByte(off: Int, b: Byte) {
    assert(size <= mem.length)
    assert(off >= 0 && off + 1 <= size, s"tried to store byte at $off to region of size $size")
    Platform.putByte(mem, Platform.BYTE_ARRAY_OFFSET + off, b)
  }

  def storeBytes(off: Int, bytes: Array[Byte]) {
    storeBytes(off, bytes, bytes.length)
  }

  def storeBytes(off: Int, bytes: Array[Byte], n: Int) {
    assert(size <= mem.length)
    assert(off >= 0 && off + n <= size, s"tried to store $n bytes at $off to region of size $size")
    assert(n <= bytes.length)
    Platform.copyMemory(bytes, Platform.BYTE_ARRAY_OFFSET, mem, Platform.BYTE_ARRAY_OFFSET + off, n)
  }

  def ensure(n: Int) {
    val newLength = size + n
    if (mem.length < newLength)
      mem = util.Arrays.copyOf(mem, (mem.length * 2).max(newLength))
  }

  def align(alignment: Int) {
    assert(alignment > 0, s"invalid alignment: $alignment")
    assert((alignment & (alignment - 1)) == 0, s"invalid alignment: $alignment") // power of 2
    offset = (offset + (alignment - 1)) & ~(alignment - 1)
  }

  def allocate(n: Int): Int = {
    val off = offset
    ensure(n)
    offset += n
    off
  }

  def alignAndAllocate(n: Int): Int = {
    align(n)
    allocate(n)
  }

  def loadBit(byteOff: Int, bitOff: Int): Boolean = {
    val b = byteOff + (bitOff >> 3)
    (loadByte(b) & (1 << (bitOff & 7))) != 0
  }
  
  def setBit(byteOff: Int, bitOff: Int) {
    val b = byteOff + (bitOff >> 3)
    storeByte(b,
      (loadByte(b) | (1 << (bitOff & 7))).toByte)
  }

  def clearBit(byteOff: Int, bitOff: Int) {
    val b = byteOff + (bitOff >> 3)
    storeByte(b,
      (loadByte(b) & ~(1 << (bitOff & 7))).toByte)
  }

  def storeBit(byteOff: Int, bitOff: Int, b: Boolean) {
    if (b)
      setBit(byteOff, bitOff)
    else
      clearBit(byteOff, bitOff)
  }

  def appendInt(i: Int) {
    storeInt(alignAndAllocate(4), i)
  }

  def appendLong(l: Long) {
    storeLong(alignAndAllocate(8), l)
  }

  def appendFloat(f: Float) {
    storeFloat(alignAndAllocate(4), f)
  }

  def appendDouble(d: Double) {
    storeDouble(alignAndAllocate(8), d)
  }

  def appendByte(b: Byte) {
    storeByte(allocate(1), b)
  }

  def appendBytes(bytes: Array[Byte]) {
    storeBytes(allocate(bytes.length), bytes)
  }

  def appendBytes(bytes: Array[Byte], n: Int) {
    assert(n <= bytes.length)
    storeBytes(allocate(n), bytes, n)
  }

  def clear() {
    offset = 0
  }

  def copy(): MemoryBuffer = new MemoryBuffer(util.Arrays.copyOf(mem, offset), offset)

  def result(): MemoryBuffer = copy()
}

case class RegionValue(region: MemoryBuffer, offset: Int)

class RegionValueBuilder(region: MemoryBuffer) {
  var start: Int = _

  val typestk = new mutable.Stack[Type]()
  val indexstk = new mutable.Stack[Int]()
  val offsetstk = new mutable.Stack[Int]()
  val elementsOffsetstk = new mutable.Stack[Int]()

  def current(): (Type, Int) = {
    val i = indexstk.head
    typestk.head match {
      case t: TStruct =>
        (t.fields(i).typ, offsetstk.head + t.byteOffsets(i))

      case t: TArray =>
        (t.elementType, elementsOffsetstk.head + i * UnsafeUtils.arrayElementSize(t.elementType))
    }
  }

  def start(top: TStruct) {
    assert(typestk.isEmpty && offsetstk.isEmpty && elementsOffsetstk.isEmpty && indexstk.isEmpty)

    region.align(top.alignment)
    val off = region.offset
    start = off
    region.allocate(top.byteSize)

    val nMissingBytes = (top.size + 7) / 8
    var i = 0
    while (i < nMissingBytes) {
      region.storeByte(off + i, 0)
      i += 1
    }

    typestk.push(top)
    offsetstk.push(off)
    indexstk.push(0)
  }

  def end(): Int = {
    val t = typestk.pop()
    offsetstk.pop()
    val last = indexstk.pop()
    assert(last == t.asInstanceOf[TStruct].size)

    assert(typestk.isEmpty && offsetstk.isEmpty && elementsOffsetstk.isEmpty && indexstk.isEmpty)

    start
  }

  def advance() {
    indexstk.push(indexstk.pop + 1)
  }

  def startStruct() {
    current() match {
      case (t: TStruct, off) =>
        val nMissingBytes = (t.size + 7) / 8
        var i = 0
        while (i < nMissingBytes) {
          region.storeByte(off + i, 0)
          i += 1
        }

        typestk.push(t)
        offsetstk.push(off)
        indexstk.push(0)
    }
  }

  def endStruct() {
    typestk.head match {
      case t: TStruct =>
        typestk.pop()
        offsetstk.pop()
        val last = indexstk.pop()
        assert(last == t.size)

        advance()
    }
  }

  def startArray(length: Int) {
    current() match {
      case (t: TArray, off) =>
        region.align(4)
        val aoff = region.offset
        region.storeInt(off, aoff)

        val nMissingBytes = (length + 7) / 8
        region.allocate(4 + nMissingBytes)

        region.storeInt(aoff, length)

        var i = 0
        while (i < nMissingBytes) {
          region.storeByte(aoff + 4 + i, 0)
          i += 1
        }

        region.align(t.elementType.alignment)
        val elementsOff = region.offset

        region.allocate(length * UnsafeUtils.arrayElementSize(t.elementType))

        typestk.push(t)
        elementsOffsetstk.push(elementsOff)
        indexstk.push(0)
        offsetstk.push(aoff)
    }
  }

  def endArray() {
    typestk.head match {
      case t: TArray =>
        typestk.pop()
        offsetstk.pop()
        elementsOffsetstk.pop()
        indexstk.pop()

        advance()
    }
  }

  def setMissing() {
    val i = indexstk.head
    typestk.head match {
      case t: TStruct =>
        region.setBit(offsetstk.head, i)
      case t: TArray =>
        region.setBit(offsetstk.head + 4, i)
    }

    advance()
  }

  def addBoolean(b: Boolean) {
    current() match {
      case (TBoolean, off) =>
        region.storeByte(off, b.toByte)
        advance()
    }
  }

  def addInt(i: Int) {
    current() match {
      case (TInt32, off) =>
        region.storeInt(off, i)
        advance()
    }
  }

  def addLong(l: Long) {
    current() match {
      case (TInt64, off) =>
        region.storeLong(off, l)
        advance()
    }
  }

  def addFloat(f: Float) {
    current() match {
      case (TFloat32, off) =>
        region.storeFloat(off, f)
        advance()
    }
  }

  def addDouble(d: Double) {
    current() match {
      case (TFloat64, off) =>
        region.storeDouble(off, d)
        advance()
    }
  }

  def addBinary(bytes: Array[Byte]) {
    current() match {
      case (TFloat64, off) =>
        region.align(4)
        val boff = region.offset
        region.storeInt(off, boff)

        region.appendInt(bytes.length)
        region.appendBytes(bytes)
        advance()
    }
  }

  def addString(s: String) {
    current() match {
      case (TString, off) =>
        region.align(4)
        val soff = region.offset
        region.storeInt(off, soff)

        val bytes = s.getBytes
        region.appendInt(bytes.length)
        region.appendBytes(bytes)

        advance()
    }
  }

  def addRow(t: TStruct, r: Row) {
    assert(r != null)
    var i = 0
    while (i < t.size) {
      addAnnotation(t.fields(i).typ, r.get(i))
      i += 1
    }
  }

  def addAnnotation(t: Type, a: Annotation) {
    if (a == null)
      setMissing()
    else
      t match {
        case TBoolean => addBoolean(a.asInstanceOf[Boolean])
        case TInt32 => addInt(a.asInstanceOf[Int])
        case TInt64 => addLong(a.asInstanceOf[Long])
        case TFloat32 => addFloat(a.asInstanceOf[Float])
        case TFloat64 => addDouble(a.asInstanceOf[Double])
        case TString => addString(a.asInstanceOf[String])
        case TBinary => addBinary(a.asInstanceOf[Array[Byte]])
        case TArray(elementType) =>
          val is = a.asInstanceOf[IndexedSeq[Annotation]]
          startArray(is.length)
          var i = 0
          while (i < is.length) {
            addAnnotation(elementType, is(i))
            i += 1
          }
          endArray()
        case t: TStruct =>
          startStruct()
          addRow(t, a.asInstanceOf[Row])
          endStruct()

        case TSet(elementType) =>
          val s = a.asInstanceOf[Set[Annotation]]
          startArray(s.size)
          s.foreach { x => addAnnotation(elementType, x) }
          endArray()

        case TDict(keyType, valueType) =>
          val m = a.asInstanceOf[Map[Annotation, Annotation]]
          startArray(m.size)
          m.foreach { case (k, v) =>
            startStruct()
            addAnnotation(keyType, k)
            addAnnotation(valueType, v)
            endStruct()
          }
          endArray()

        case TVariant =>
          val v = a.asInstanceOf[Variant]
          startStruct()
          addString(v.contig)
          addInt(v.start)
          addString(v.ref)
          startArray(v.altAlleles.length)
          var i = 0
          while (i < v.altAlleles.length) {
            addAnnotation(TAltAllele, v.altAlleles(i))
            i += 1
          }
          endArray()
          endStruct()

        case TAltAllele =>
          val aa = a.asInstanceOf[AltAllele]
          startStruct()
          addString(aa.ref)
          addString(aa.alt)
          endStruct()

        case TCall =>
          addInt(a.asInstanceOf[Int])

        case TGenotype =>
          val g = a.asInstanceOf[Genotype]
          startStruct()

          val unboxedGT = g._unboxedGT
          if (unboxedGT >= 0)
            addInt(unboxedGT)
          else
            setMissing()

          val unboxedAD = g._unboxedAD
          if (unboxedAD == null)
            setMissing()
          else {
            startArray(unboxedAD.length)
            var i = 0
            while (i < unboxedAD.length) {
              addInt(unboxedAD(i))
              i += 1
            }
            endArray()
          }

          val unboxedDP = g._unboxedDP
          if (unboxedDP >= 0)
            addInt(unboxedDP)
          else
            setMissing()

          val unboxedGQ = g._unboxedGQ
          if (unboxedGQ >= 0)
            addInt(unboxedGQ)
          else
            setMissing()

          val unboxedPX = g._unboxedPX
          if (unboxedPX == null)
            setMissing()
          else {
            startArray(unboxedPX.length)
            var i = 0
            while (i < unboxedPX.length) {
              addInt(unboxedPX(i))
              i += 1
            }
            endArray()
          }

          addBoolean(g._fakeRef)
          addBoolean(g._isLinearScale)
          endStruct()

        case TLocus =>
          val l = a.asInstanceOf[Locus]
          startStruct()
          addString(l.contig)
          addInt(l.position)
          endStruct()

        case TInterval =>
          val i = a.asInstanceOf[Interval[Locus]]
          startStruct()
          addAnnotation(TLocus, i.start)
          addAnnotation(TLocus, i.end)
          endStruct()
      }
  }

  def result(): RegionValue = RegionValue(region, start)
}
