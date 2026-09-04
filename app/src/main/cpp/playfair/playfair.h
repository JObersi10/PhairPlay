/*
 * This file is part of the PlayFair FairPlay implementation by Esteban Kubata,
 * https://github.com/EstebanKubata/playfair, obtained here via RPiPlay
 * (https://github.com/FD-/RPiPlay, lib/playfair).
 *
 * PlayFair is licensed under the GNU General Public License version 3.
 * Because PhairPlay links this code into libplayfair.so and distributes the
 * result, PhairPlay as a whole is likewise GPL-3.0-or-later. See ../../../../../LICENSE
 * and THIRD_PARTY_NOTICES.md at the repository root.
 *
 * The upstream sources carry no copyright header; this notice was added by the
 * PhairPlay maintainers to record provenance, and does not alter the terms.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef PLAYFAIR_H
#define PLAYFAIR_H

void playfair_decrypt(unsigned char* message3, unsigned char* cipherText, unsigned char* keyOut);

#endif
