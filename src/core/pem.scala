/*
    Gastronomy, version 0.9.0. Copyright 2018-21 Jon Pretty, Propensive OÜ.

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
import gossamer.*

case class Pem(kind: Txt, data: Bytes):
  def serialize: Txt = Seq(
    Seq(str"-----BEGIN $kind-----"),
    data.grouped(48).to(Seq).map(_.encode[Base64]),
    Seq(str"-----END $kind-----")
  ).flatten.join(str"\n")

object Pem:
  def parse(string: Txt): Pem throws PemParseError =
    val lines = string.trim.nn.cut("\n")
    
    val label = lines.head match
      case s"-----BEGIN $label-----" => label.show
      case _                         => throw PemParseError(str"the BEGIN line could not be found")
    
    lines.tail.indexWhere {
      case s"-----END $label-----" => true
      case _                       => false
    } match
      case -1  =>
        throw PemParseError(str"the message's END line could not be found")
      case idx =>
        val joined: Txt = lines.tail.take(idx).join
        try Pem(label, joined.decode[Base64])
        catch Exception => throw PemParseError(str"could not parse Base64 PEM message")