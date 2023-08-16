/*
    Gastronomy, version [unreleased]. Copyright 2023 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package gastronomy

import rudiments.*
import fulminate.*
import gossamer.*
import anticipation.*
import spectacular.*
import hieroglyph.*, textWidthCalculation.uniform

import scala.collection.*
import scala.compiletime.*, ops.int.*
import scala.deriving.*

import java.util as ju
import ju.zip as juz
import java.security.*
import javax.crypto.Mac, javax.crypto.spec.SecretKeySpec
import ju.Base64.{getEncoder as Base64Encoder, getDecoder as Base64Decoder}
import java.lang as jl

case class Base32Alphabet(chars: IArray[Char], padding: Char)
//trait Base64Alphabet(val chars: IArray[Char], val padding: Char)

sealed trait HashScheme[Size <: Nat]

sealed trait Md5 extends HashScheme[16]

sealed trait Crc32 extends HashScheme[32]

type ByteCount[Bits] <: Nat = Bits match
  case 224 => 28
  case 256 => 32
  case 384 => 48
  case 512 => 64

sealed trait Sha2[Bits <: 224 | 256 | 384 | 512] extends HashScheme[ByteCount[Bits]]
sealed trait Sha1 extends HashScheme[20]
sealed trait Sha384 extends HashScheme[48]
sealed trait Sha512 extends HashScheme[64]

object Crc32:
  given HashFunction[Crc32] = Crc32HashFunction

object Md5:
  given HashFunction[Md5] = MdHashFunction(t"MD5", t"HmacMD5")

object Sha1:
  given HashFunction[Sha1] = MdHashFunction(t"SHA1", t"HmacSHA1")

object Sha2:
  given sha2[Bits <: 224 | 256 | 384 | 512: ValueOf]: HashFunction[Sha2[Bits]] =
    MdHashFunction(t"SHA-${valueOf[Bits]}", t"HmacSHA${valueOf[Bits]}")

trait Encodable:
  val bytes: Bytes
  def encodeAs[ES <: EncodingScheme: ByteEncoder]: Text = bytes.encodeAs[ES]

object Hmac:
  given Show[Hmac[?]] = hmac => t"Hmac(${hmac.bytes.encodeAs[Base64]})"

case class Hmac[A <: HashScheme[?]](bytes: Bytes) extends Encodable, Shown[Hmac[?]]

trait HashFunction[A <: HashScheme[?]]:
  def name: Text
  def hmacName: Text
  def init: DigestAccumulator
  def initHmac: Mac

case class MdHashFunction[A <: HashScheme[?]](name: Text, hmacName: Text) extends HashFunction[A]:
  def init: DigestAccumulator = new MessageDigestAccumulator(MessageDigest.getInstance(name.s).nn)
  def initHmac: Mac = Mac.getInstance(hmacName.s).nn

case object Crc32HashFunction extends HashFunction[Crc32]:
  def init: DigestAccumulator = new DigestAccumulator:
    private val state: juz.CRC32 = juz.CRC32()
    def append(bytes: Bytes): Unit = state.update(bytes.mutable(using Unsafe))
    
    def digest(): Bytes =
      val int = state.getValue()
      IArray[Byte]((int >> 24).toByte, (int >> 16).toByte, (int >> 8).toByte, int.toByte)
  def name: Text = t"CRC32"
  def hmacName: Text = t"HMAC-CRC32"
  def initHmac: Mac = throw Mistake(msg"this has not been implemented")
    

object Digest:
  given Show[Digest[?]] = digest => t"Digest(${digest.bytes.encodeAs[Base64]})"

case class Digest[A <: HashScheme[?]](bytes: Bytes) extends Encodable, Shown[Digest[?]]:
  override def equals(that: Any) = that.asMatchable match
    case digest: Digest[?] => val left: Array[Byte] = bytes.mutable(using Unsafe)
                              val right: Array[Byte] = bytes.mutable(using Unsafe)
                              ju.Arrays.equals(left, right)
    case _                 => false
  
  override def hashCode: Int = bytes.hashCode

trait Digestible2:
  given [T](using digestible: Digestible[T]): Digestible[Maybe[T]] = (acc, value) =>
    value.mm(digestible.digest(acc, _))

object Digestible extends Digestible2:
  private transparent inline def deriveProduct(acc: DigestAccumulator, tuple: Tuple): Unit =
    inline tuple match
      case EmptyTuple => ()
      case cons: (? *: ?) => cons match
        case head *: tail =>
          summonInline[Digestible[head.type]].digest(acc, head)
          deriveProduct(acc, tail)

  private transparent inline def deriveSum
      [TupleType <: Tuple, DerivedType]
      (ordinal: Int)
      : Digestible[DerivedType] =
    inline erasedValue[TupleType] match
      case _: (head *: tail) =>
        if ordinal == 0
        then summonInline[Digestible[head]].asInstanceOf[Digestible[DerivedType]]
        else deriveSum[tail, DerivedType](ordinal - 1)

  inline given derived
      [DerivationType]
      (using mirror: Mirror.Of[DerivationType])
      : Digestible[DerivationType] =
    inline mirror match
      case given Mirror.ProductOf[DerivationType & Product] =>
        (acc: DigestAccumulator, value: DerivationType) => (value.asMatchable: @unchecked) match
          case value: Product => deriveProduct(acc, Tuple.fromProductTyped(value))
    
      case s: Mirror.SumOf[DerivationType] =>
        (acc: DigestAccumulator, value: DerivationType) =>
          deriveSum[s.MirroredElemTypes, DerivationType](s.ordinal(value)).digest(acc, value)

  given[T: Digestible]: Digestible[Iterable[T]] =
    (acc, xs) => xs.foreach(summon[Digestible[T]].digest(acc, _))

  given Digestible[Int] =
    (acc, n) => acc.append((24 to 0 by -8).map(n >> _).map(_.toByte).toArray.immutable(using Unsafe))
  
  given Digestible[Long] =
    (acc, n) => acc.append((52 to 0 by -8).map(n >> _).map(_.toByte).toArray.immutable(using Unsafe))
  
  given Digestible[Double] =
    (acc, n) => summon[Digestible[Long]].digest(acc, jl.Double.doubleToRawLongBits(n))
  
  given Digestible[Float] =
    (acc, n) => summon[Digestible[Int]].digest(acc, jl.Float.floatToRawIntBits(n))
  
  given Digestible[Boolean] = (acc, n) => acc.append(IArray(if n then 1.toByte else 0.toByte))
  given Digestible[Byte] = (acc, n) => acc.append(IArray(n))
  given Digestible[Short] = (acc, n) => acc.append(IArray((n >> 8).toByte, n.toByte))
  given Digestible[Char] = (acc, n) => acc.append(IArray((n >> 8).toByte, n.toByte))
  given Digestible[Text] = (acc, s) => acc.append(s.bytes(using charEncoders.utf8))
  given Digestible[Bytes] = _.append(_)
  given Digestible[Iterable[Bytes]] = (acc, stream) => stream.foreach(acc.append(_))
  given Digestible[Digest[?]] = (acc, d) => acc.append(d.bytes)

trait Digestible[-T]:
  def digest(acc: DigestAccumulator, value: T): Unit

case class Digester(run: DigestAccumulator => Unit):
  def apply[A <: HashScheme[?]: HashFunction]: Digest[A] =
    val acc = summon[HashFunction[A]].init
    run(acc)
    Digest(acc.digest())
  
  def digest[T: Digestible](value: T): Digester =
    Digester:
      acc =>
        run(acc)
        summon[Digestible[T]].digest(acc, value)

trait DigestAccumulator:
  def append(bytes: Bytes): Unit
  def digest(): Bytes

class MessageDigestAccumulator(md: MessageDigest) extends DigestAccumulator:
  private val messageDigest: MessageDigest = md
  def append(bytes: Bytes): Unit = messageDigest.update(bytes.mutable(using Unsafe))
  def digest(): Bytes = messageDigest.digest.nn.immutable(using Unsafe)

trait EncodingScheme
trait Base64 extends EncodingScheme
trait Base64Url extends EncodingScheme
trait Base32 extends EncodingScheme
trait Hex extends EncodingScheme
trait Binary extends EncodingScheme

package alphabets:
  package base32:
    given default: Base32Alphabet = Base32Alphabet(t"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".chars, '=')
    given zBase32: Base32Alphabet = Base32Alphabet(t"ybndrfg8ejkmcpqxot1uwisza345h769".chars, '=')

  //package base64:

object ByteEncoder:
  private val HexLookup: Bytes = IArray.from(t"0123456789ABCDEF".bytes(using charEncoders.ascii))

  given ByteEncoder[Hex] = bytes =>
    val array = new Array[Byte](bytes.length*2)
    bytes.indices.foreach:
      idx =>
        array(2*idx) = HexLookup((bytes(idx) >> 4) & 0xf)
        array(2*idx + 1) = HexLookup(bytes(idx) & 0xf)
    
    Text(String(array, "UTF-8"))


  given (using alphabet: Base32Alphabet): ByteEncoder[Base32] = bytes =>
    val buf: StringBuilder = StringBuilder()
    
    @tailrec
    def recur(acc: Int, index: Int, unused: Int): Text =
      buf.append(alphabet.chars((acc >> 11) & 31))
      
      if index >= bytes.length then
        buf.append(alphabet.chars((acc >> 6) & 31))
        if unused == 5 then buf.append(alphabet.chars((acc >> 1) & 31))
        if buf.length%8 != 0 then for i <- 0 until (8 - buf.length%8) do buf.append(alphabet.padding)
        buf.toString.tt
      else
        if unused >= 8 then recur((acc << 5) + (bytes(index) << (unused - 3)), index + 1, unused - 3)
        else recur(acc << 5, index, unused + 5)

    if bytes.isEmpty then t"" else recur(bytes.head.toInt << 8, 1, 8)

  given ByteEncoder[Base64] = bytes => Text(Base64Encoder.nn.encodeToString(bytes.to(Array)).nn)
  
  given ByteEncoder[Binary] = bytes =>
    val buf = StringBuilder()
    bytes.foreach:
      byte => buf.add(Integer.toBinaryString(byte).nn.show.fit(8, Rtl, '0'))
    buf.text

  given ByteEncoder[Base64Url] = bytes =>
    Text(Base64Encoder.nn.encodeToString(bytes.to(Array)).nn)
      .tr('+', '-')
      .tr('/', '_')
      .upto(_ == '=')

trait ByteDecoder[ES <: EncodingScheme]:
  def decode(value: Text): Bytes

trait ByteEncoder[ES <: EncodingScheme]:
  def encode(bytes: Bytes): Text

object ByteDecoder:
  given ByteDecoder[Base64] = value => Base64Decoder.nn.decode(value.s).nn.immutable(using Unsafe)
  
  given ByteDecoder[Hex] = value =>
    import java.lang.Character.digit
    val data = Array.fill[Byte](value.length/2)(0)
    
    (0 until value.length by 2).foreach:
      i =>
        try data(i/2) = ((digit(value(i), 16) << 4) + digit(value(i + 1), 16)).toByte
        catch case e: OutOfRangeError => throw Mistake(msg"every accessed element should be within range")

    data.immutable(using Unsafe)

extension (value: Text)
  def decode[T <: EncodingScheme: ByteDecoder]: Bytes = summon[ByteDecoder[T]].decode(value)

extension [T](value: T)
  def digest[A <: HashScheme[?]: HashFunction](using Digestible[T]): Digest[A] =
    val digester = Digester(summon[Digestible[T]].digest(_, value))
    digester.apply

  def hmac[A <: HashScheme[?]: HashFunction](key: Bytes)(using ByteCodec[T]): Hmac[A] =
    val mac = summon[HashFunction[A]].initHmac
    mac.init(SecretKeySpec(key.to(Array), summon[HashFunction[A]].name.s))
    Hmac(mac.doFinal(summon[ByteCodec[T]].encode(value).mutable(using Unsafe)).nn.immutable(using Unsafe))

extension (bytes: Bytes)
  def encodeAs[E <: EncodingScheme: ByteEncoder]: Text = summon[ByteEncoder[E]].encode(bytes)
