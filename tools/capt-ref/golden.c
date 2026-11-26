/*
 * Generates golden HiSCoA test vectors from captdriver's reference C compressor so the
 * Kotlin port can be checked byte-for-byte in CI. Output format, repeated per case
 * (little-endian u32): line_size, lines, in_len, in[in_len], out_len, out[out_len].
 */
#include "hiscoa-compress.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t rng = 12345;
static uint32_t next(void) { rng = rng * 1103515245u + 12345u; return (rng >> 16) & 0x7FFF; }

static void put32(FILE *f, uint32_t v) {
  unsigned char b[4] = { v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF };
  fwrite(b, 1, 4, f);
}

static void emit(FILE *f, const uint8_t *in, unsigned line_size, unsigned lines) {
  size_t in_len = (size_t) line_size * lines;
  size_t cap = 2 * in_len + 64;
  uint8_t *out = calloc(1, cap);
  size_t n = hiscoa_compress_band(out, cap, in, line_size, lines, HISCOA_EOB_NORMAL, &hiscoa_default_params);
  put32(f, line_size); put32(f, lines); put32(f, (uint32_t) in_len);
  fwrite(in, 1, in_len, f);
  put32(f, (uint32_t) n);
  fwrite(out, 1, n, f);
  free(out);
}

int main(int argc, char **argv) {
  FILE *f = fopen(argc > 1 ? argv[1] : "hiscoa_cases.bin", "wb");
  if (!f) return 1;
  const unsigned sizes[][2] = { {595, 160}, {595, 37}, {75, 70}, {612, 160} };
  for (unsigned s = 0; s < 4; ++s) {
    unsigned ls = sizes[s][0], nl = sizes[s][1];
    size_t len = (size_t) ls * nl;
    uint8_t *b = calloc(1, len);
    for (int pattern = 0; pattern < 6; ++pattern) {
      for (size_t i = 0; i < len; ++i) {
        unsigned x = i % ls, y = i / ls;
        switch (pattern) {
        case 0: b[i] = 0; break;
        case 1: b[i] = 0xFF; break;
        case 2: b[i] = (next() % 20 == 0) ? (uint8_t) next() : 0; break;          /* sparse */
        case 3: b[i] = (uint8_t) next(); break;                                  /* noise */
        case 4: b[i] = ((x / 3 + y / 9) % 5 == 0) ? 0x3C ^ (uint8_t) (y & 7) : 0; break; /* text-ish */
        case 5: b[i] = (uint8_t) ((y % 8 < 4) ? 0xAA : 0x55); break;            /* dither */
        }
      }
      emit(f, b, ls, nl);
    }
    free(b);
  }
  fclose(f);
  return 0;
}
