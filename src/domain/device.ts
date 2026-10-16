import {utf8Size} from './protocol';

/** The Windows app rejects names longer than 80 UTF-8 bytes. */
const MAX_NAME_BYTES = 80;

/**
 * Name shown in the PC's pairing request, such as "Samsung SM-S918B", so the
 * person approving can tell which phone is asking.
 */
export function deviceDisplayName(constants: {Brand?: string; Manufacturer?: string; Model?: string}): string {
  const brand = (constants.Brand || constants.Manufacturer || '').trim();
  const model = (constants.Model || '').trim();
  const brandLabel = brand ? brand.charAt(0).toUpperCase() + brand.slice(1) : '';
  const raw = brand && model.toLowerCase().startsWith(brand.toLowerCase()) ? model : `${brandLabel} ${model}`;
  let name = Array.from(raw)
    .filter(character => {
      const point = character.codePointAt(0)!;
      return point >= 0x20 && (point < 0x7f || point > 0x9f);
    })
    .join('')
    .replace(/\s+/g, ' ')
    .trim();
  while (name && utf8Size(name) > MAX_NAME_BYTES) {
    name = Array.from(name).slice(0, -1).join('').trim();
  }
  return name || 'Celular Android';
}
