/*
    Gastronomy, version [unreleased]. Copyright 2024 Jon Pretty, Propensive OÜ.

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
import vacuous.*
import fulminate.*
import wisteria.*
import contingency.*
import gossamer.*
import anticipation.*
import spectacular.*
import hieroglyph.*, textMetrics.uniform

import scala.collection.*
import scala.compiletime.*, ops.int.*

import java.util as ju
import ju.zip as juz
import java.security.*
import javax.crypto.Mac, javax.crypto.spec.SecretKeySpec
import ju.Base64.{getEncoder as Base64Encoder, getDecoder as Base64Decoder}
import java.lang as jl

case class Base32Alphabet(chars: IArray[Char], padding: Text)
case class HexAlphabet(chars: IArray[Char])

sealed trait HashScheme[Size <: Nat]

sealed trait Md5 extends HashScheme[16]

sealed trait Crc32 extends HashScheme[32]

type ByteCount[BitsType] <: Nat = BitsType match
  case 224 => 28
  case 256 => 32
  case 384 => 48
  case 512 => 64

sealed trait Sha2[BitsType <: 224 | 256 | 384 | 512] extends HashScheme[ByteCount[BitsType]]
sealed trait Sha1 extends HashScheme[20]
sealed trait Sha384 extends HashScheme[48]
sealed trait Sha512 extends HashScheme[64]

object Crc32:
  given hashFunction: HashFunction[Crc32] = Crc32HashFunction

object Md5:
  given hashFunction: HashFunction[Md5] = MdHashFunction(t"MD5", t"HmacMD5")

object Sha1:
  given hashFunction: HashFunction[Sha1] = MdHashFunction(t"SHA1", t"HmacSHA1")

object Sha2:
  given hashFunction[BitsType <: 224 | 256 | 384 | 512: ValueOf]: HashFunction[Sha2[BitsType]] =
    MdHashFunction(t"SHA-${valueOf[BitsType]}", t"HmacSHA${valueOf[BitsType]}")

trait Encodable:
  val bytes: Bytes
  def encodeAs[SchemeType <: EncodingScheme: ByteEncoder]: Text = bytes.encodeAs[SchemeType]

object Hmac:
  given [HmacType <: HashScheme[?]] => Hmac[HmacType] is Showable = hmac => t"Hmac(${hmac.bytes.encodeAs[Base64]})"

case class Hmac[HashType <: HashScheme[?]](bytes: Bytes) extends Encodable

trait HashFunction[HashType <: HashScheme[?]]:
  def name: Text
  def hmacName: Text
  def init(): DigestAccumulator
  def hmac0: Mac

case class MdHashFunction[HashType <: HashScheme[?]](name: Text, hmacName: Text) extends HashFunction[HashType]:
  def init(): DigestAccumulator = new MessageDigestAccumulator(MessageDigest.getInstance(name.s).nn)
  def hmac0: Mac = Mac.getInstance(hmacName.s).nn

case object Crc32HashFunction extends HashFunction[Crc32]:
  def init(): DigestAccumulator = new DigestAccumulator:
    private val state: juz.CRC32 = juz.CRC32()
    def append(bytes: Bytes): Unit = state.update(bytes.mutable(using Unsafe))

    def digest(): Bytes =
      val int = state.getValue()
      IArray[Byte]((int >> 24).toByte, (int >> 16).toByte, (int >> 8).toByte, int.toByte)

  def name: Text = t"CRC32"
  def hmacName: Text = t"HMAC-CRC32"
  def hmac0: Mac = throw Panic(msg"this has not been implemented")


object Digest:
  given [DigestType <: HashScheme[?]] => Digest[DigestType] is Showable = _.bytes.encodeAs[Base64]

case class Digest[HashType <: HashScheme[?]](bytes: Bytes) extends Encodable:
  override def equals(that: Any) = that.asMatchable match
    case digest: Digest[?] => bytes.sameElements(digest.bytes)
    case _                 => false

  override def hashCode: Int = ju.Arrays.hashCode(bytes.mutable(using Unsafe): Array[Byte])

object Digestible extends Derivable[Digestible]:
  inline def join[DerivationType <: Product: ProductReflection]: DerivationType is Digestible =
    (accumulator, value) => fields(value):
      [FieldType] => field => context.digest(accumulator, field)

  inline def split[DerivationType: SumReflection]: DerivationType is Digestible = (accumulator, value) =>
    variant(value):
      [VariantType <: DerivationType] => variant =>
        int.digest(accumulator, index)
        context.digest(accumulator, variant)

  given [ValueType: Digestible](using util.NotGiven[Unset.type <:< ValueType])
      => Optional[ValueType] is Digestible as optional =
    (acc, value) => value.let(ValueType.digest(acc, _))

  given [ValueType: Digestible] => Iterable[ValueType] is Digestible as iterable =
    (accumulator, iterable) => iterable.each(ValueType.digest(accumulator, _))

  given Int is Digestible as int =
    (acc, n) => acc.append((24 to 0 by -8).map(n >> _).map(_.toByte).toArray.immutable(using Unsafe))

  given Long is Digestible as long =
    (acc, n) => acc.append((52 to 0 by -8).map(n >> _).map(_.toByte).toArray.immutable(using Unsafe))

  given Double is Digestible =
    (acc, n) => long.digest(acc, jl.Double.doubleToRawLongBits(n))

  given Float is Digestible =
    (acc, n) => int.digest(acc, jl.Float.floatToRawIntBits(n))

  given Boolean is Digestible = (acc, n) => acc.append(IArray(if n then 1.toByte else 0.toByte))
  given Byte is Digestible = (acc, n) => acc.append(IArray(n))
  given Short is Digestible = (acc, n) => acc.append(IArray((n >> 8).toByte, n.toByte))
  given Char is Digestible = (acc, n) => acc.append(IArray((n >> 8).toByte, n.toByte))
  given Text is Digestible = (acc, s) => acc.append(s.bytes(using charEncoders.utf8))
  given Bytes is Digestible = _.append(_)
  given Digest[?] is Digestible = (acc, d) => acc.append(d.bytes)

trait Digestible:
  type Self
  def digest(acc: DigestAccumulator, value: Self): Unit

case class Digester(run: DigestAccumulator => Unit):
  def apply[HashType <: HashScheme[?]: HashFunction]: Digest[HashType] =
    summon[HashFunction[HashType]].init().pipe: accumulator =>
      run(accumulator)
      Digest(accumulator.digest())

  def digest[ValueType: Digestible](value: ValueType): Digester = Digester:
    accumulator =>
      run(accumulator)
      ValueType.digest(accumulator, value)

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

package hashFunctions:
  given crc32: HashFunction[Crc32] = Crc32.hashFunction
  given md5: HashFunction[Md5] = Md5.hashFunction
  given sha1: HashFunction[Sha1] = Sha1.hashFunction

  given sha2[BitsType <: 224 | 256 | 384 | 512: ValueOf]: HashFunction[Sha2[BitsType]] =
    Sha2.hashFunction[BitsType]

package alphabets:
  package base32:
    given default: Base32Alphabet = Base32Alphabet(t"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".chars, t"=")
    given zBase32: Base32Alphabet = Base32Alphabet(t"ybndrfg8ejkmcpqxot1uwisza345h769".chars, t"=")

    given zBase32Unpadded: Base32Alphabet =
      Base32Alphabet(t"ybndrfg8ejkmcpqxot1uwisza345h769".chars, t"")

  package hex:
    given upperCase: HexAlphabet = HexAlphabet(t"0123456789ABCDEF".chars)
    given lowerCase: HexAlphabet = HexAlphabet(t"0123456789abcdef".chars)
    given bioctal: HexAlphabet = HexAlphabet(t"01234567cjzwfsbv".chars)

object ByteEncoder:
  private val HexLookup: Bytes = IArray.from(t"0123456789ABCDEF".bytes(using charEncoders.ascii))

  given (using alphabet: HexAlphabet): ByteEncoder[Hex] = bytes =>
    val array = new Array[Char](bytes.length*2)

    bytes.indices.each: index =>
      array(2*index) = alphabet.chars((bytes(index) >> 4) & 0xf)
      array(2*index + 1) = alphabet.chars(bytes(index) & 0xf)

    Text(String(array))


  given (using alphabet: Base32Alphabet): ByteEncoder[Base32] = bytes =>
    val buf: StringBuilder = StringBuilder()

    @tailrec
    def recur(acc: Int, index: Int, unused: Int): Text =
      buf.append(alphabet.chars((acc >> 11) & 31))

      if index >= bytes.length then
        buf.append(alphabet.chars((acc >> 6) & 31))
        if unused == 5 then buf.append(alphabet.chars((acc >> 1) & 31))

        if buf.length%8 != 0
        then for i <- 0 until (8 - buf.length%8) do buf.append(alphabet.padding)

        buf.toString.tt

      else
        if unused >= 8 then recur((acc << 5) + (bytes(index) << (unused - 3)), index + 1, unused - 3)
        else recur(acc << 5, index, unused + 5)

    if bytes.isEmpty then t"" else recur(bytes.head.toInt << 8, 1, 8)

  given ByteEncoder[Base64] = bytes => Text(Base64Encoder.nn.encodeToString(bytes.to(Array)).nn)

  given ByteEncoder[Binary] = bytes =>
    Text.construct:
      bytes.each:
        byte => append(Integer.toBinaryString(byte).nn.show.fit(8, Rtl, '0'))

  given ByteEncoder[Base64Url] = bytes =>
    Base64Encoder.nn.encodeToString(bytes.to(Array)).nn.tt
      .tr('+', '-')
      .tr('/', '_')
      .upto(_ == '=')

trait ByteDecoder[SchemeType <: EncodingScheme]:
  def decode(value: Text): Bytes

trait ByteEncoder[SchemeType <: EncodingScheme]:
  def encode(bytes: Bytes): Text

object ByteDecoder:
  given (using Errant[DecodeError]): ByteDecoder[Base64] = value =>
    try Base64Decoder.nn.decode(value.s).nn.immutable(using Unsafe)
    catch case _: IllegalArgumentException => abort(DecodeError(t"an invalid BASE-64 character found"))

  given ByteDecoder[Hex] = value =>
    import java.lang.Character.digit
    val data = Array.fill[Byte](value.length/2)(0)

    (0 until value.length by 2).each: i =>
      data(i/2) = unsafely(((digit(value.at(i).vouch, 16) << 4) + digit(value.at(i + 1).vouch, 16)).toByte)

    data.immutable(using Unsafe)

extension (value: Text)
  def decode[SchemeType <: EncodingScheme: ByteDecoder]: Bytes = summon[ByteDecoder[SchemeType]].decode(value)

extension [ValueType: Digestible](value: ValueType)
  def digest[HashType <: HashScheme[?]: HashFunction]: Digest[HashType] =
    val digester = Digester(ValueType.digest(_, value))
    digester.apply

extension [ValueType: ByteCodec](value: ValueType)
  def hmac[HashType <: HashScheme[?]: HashFunction](key: Bytes): Hmac[HashType] =
    val mac = HashType.hmac0
    mac.init(SecretKeySpec(key.to(Array), HashType.name.s))

    Hmac:
      unsafely(mac.doFinal(ValueType.encode(value).mutable).nn.immutable)

extension (bytes: Bytes)
  def encodeAs[SchemeType <: EncodingScheme: ByteEncoder]: Text = summon[ByteEncoder[SchemeType]].encode(bytes)
