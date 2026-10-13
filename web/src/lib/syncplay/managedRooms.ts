/**
 * Syncplay's managed (controlled) rooms: only the room's operators can play, pause, seek or change the playlist.
 * A managed room is named `+<name>:<HASH>`, where the server derives the 12-character hash from the name, the
 * operator password and its own salt; clients never compute it.
 */

const NAME = /^\+(.*):(\w{12})$/;
const PASSWORD = /^[A-Z]{2}-\d{3}-\d{3}$/;

/** Syncplay's limit on room names; the `+` and `:HASH` take 14 of them. */
export const MAX_ROOM_LENGTH = 35;
export const MAX_BASE_NAME_LENGTH = MAX_ROOM_LENGTH - 14;

export const isManaged = (room: string): boolean => NAME.test(room);

/** The name people chose: `movie night` for `+movie night:D6A85289CDB5`; other names as they are. */
export const display = (room: string): string => NAME.exec(room)?.[1] ?? room;

/** [display] with a lock in front of managed rooms, for one-line labels. */
export const label = (room: string): string => (isManaged(room) ? `🔒 ${display(room)}` : room);

/** Whether [baseName] can become a managed room: 1 to MAX_BASE_NAME_LENGTH characters once trimmed. */
export function isValidBaseName(baseName: string): boolean {
  const length = baseName.trim().length;
  return length >= 1 && length <= MAX_BASE_NAME_LENGTH;
}

export const isValidPassword = (password: string): boolean => PASSWORD.test(password);

/** What someone typed as a password, the way the server expects it: trimmed and uppercase. */
export const normalizePassword = (input: string): string => input.trim().toUpperCase();

/** Uniform random integers below [bound] from a secure source. */
export type RandomInt = (bound: number) => number;

const secureRandomInt: RandomInt = (bound) => {
  const limit = Math.floor(0x100000000 / bound) * bound;
  const word = new Uint32Array(1);
  do crypto.getRandomValues(word);
  while (word[0] >= limit);
  return word[0] % bound;
};

/** A new operator password like `AB-123-456`, from a secure random source. */
export function generatePassword(random: RandomInt = secureRandomInt): string {
  const letter = () => String.fromCharCode(65 + random(26));
  const digits = () => String(random(1000)).padStart(3, "0");
  return `${letter()}${letter()}-${digits()}-${digits()}`;
}
