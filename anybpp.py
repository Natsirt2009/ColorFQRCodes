
#!/usr/bin/env python3
"""Configurable multicolor 2bpp–8bpp code with Reed-Solomon ECC."""

import argparse
import colorsys
import math
import struct
import zlib

from PIL import Image, ImageDraw
from reedsolo import RSCodec

MIN_SIZE = 21
MAX_SIZE = 177
SIZE_STEP = 4
QUIET_ZONE = 4
DEFAULT_SCALE = 12

ECC_BYTES = 16
MAX_DATA_PER_BLOCK = 255 - ECC_BYTES


def make_palette(bpp):
    """Create a deterministic palette containing exactly 2**bpp colors."""
    if not 1 <= bpp <= 8:
        raise ValueError("bpp must be between 1 and 8.")

    count = 1 << bpp
    colors = [(20, 20, 24), (245, 245, 240)]

    for i in range(2, count):
        hue = (i - 2) / max(1, count - 2)
        saturation = 0.78
        value = 0.92
        rgb = colorsys.hsv_to_rgb(hue, saturation, value)
        colors.append(tuple(round(c * 255) for c in rgb))

    return colors[:count]


def build_layout(size):
    """Return the matrix, reserved-pattern map, and data positions."""
    grid = [[0] * size for _ in range(size)]
    reserved = [[False] * size for _ in range(size)]

    def put(x, y, color):
        if 0 <= x < size and 0 <= y < size:
            grid[y][x] = color
            reserved[y][x] = True

    # Three finder patterns and their separators.
    for ox, oy in ((0, 0), (size - 7, 0), (0, size - 7)):
        for dy in range(-1, 8):
            for dx in range(-1, 8):
                x, y = ox + dx, oy + dy
                if not (0 <= x < size and 0 <= y < size):
                    continue

                if 0 <= dx < 7 and 0 <= dy < 7:
                    outer = dx in (0, 6) or dy in (0, 6)
                    center = 2 <= dx <= 4 and 2 <= dy <= 4
                    color = 0 if outer or center else 1
                else:
                    color = 1

                put(x, y, color)

    # Timing guides.
    for i in range(8, size - 8):
        put(i, 8, 0 if i % 2 == 0 else 1)
        put(8, i, 0 if i % 2 == 0 else 1)

    # Zigzag traversal of all unreserved modules.
    positions = []
    right = size - 1
    upward = True

    while right >= 0:
        columns = [right]
        if right > 0:
            columns.append(right - 1)

        rows = range(size - 1, -1, -1) if upward else range(size)

        for y in rows:
            for x in columns:
                if not reserved[y][x]:
                    positions.append((x, y))

        upward = not upward
        right -= 2

    return grid, positions


def calculate_capacity(total_bytes):
    """Maximum data bytes fitting after per-block RS parity."""
    for data_bytes in range(total_bytes, -1, -1):
        blocks = max(1, math.ceil(data_bytes / MAX_DATA_PER_BLOCK))
        if data_bytes + blocks * ECC_BYTES <= total_bytes:
            return data_bytes
    return 0


def rs_encode(data):
    """Encode independent RS blocks and concatenate their codewords."""
    codec = RSCodec(ECC_BYTES)
    output = bytearray()

    for start in range(0, len(data), MAX_DATA_PER_BLOCK):
        output.extend(codec.encode(data[start:start + MAX_DATA_PER_BLOCK]))

    return bytes(output)


def rs_decode(codeword, data_length):
    """Decode RS blocks, returning their concatenated data."""
    codec = RSCodec(ECC_BYTES)
    output = bytearray()
    offset = 0
    remaining = data_length

    while remaining:
        chunk_data_len = min(remaining, MAX_DATA_PER_BLOCK)
        chunk_len = chunk_data_len + ECC_BYTES
        chunk = codeword[offset:offset + chunk_len]

        if len(chunk) != chunk_len:
            raise ValueError("Truncated Reed-Solomon block.")

        decoded = codec.decode(chunk)
        output.extend(decoded[0])

        offset += chunk_len
        remaining -= chunk_data_len

    return bytes(output)


def bytes_to_symbols(data, bpp):
    """Pack a byte stream into bpp-bit symbols, most significant bit first."""
    mask = (1 << bpp) - 1
    accumulator = 0
    bit_count = 0
    symbols = []

    for byte in data:
        accumulator = (accumulator << 8) | byte
        bit_count += 8

        while bit_count >= bpp:
            bit_count -= bpp
            symbols.append((accumulator >> bit_count) & mask)
            accumulator &= (1 << bit_count) - 1

    if bit_count:
        symbols.append((accumulator << (bpp - bit_count)) & mask)

    return symbols


def symbols_to_bytes(symbols, bpp, byte_count):
    """Reverse bytes_to_symbols, extracting exactly byte_count bytes."""
    accumulator = 0
    bit_count = 0
    output = bytearray()

    for symbol in symbols:
        accumulator = (accumulator << bpp) | symbol
        bit_count += bpp

        while bit_count >= 8 and len(output) < byte_count:
            bit_count -= 8
            output.append((accumulator >> bit_count) & 0xFF)
            accumulator &= (1 << bit_count) - 1

        if len(output) == byte_count:
            break

    if len(output) != byte_count:
        raise ValueError("Not enough module bits to reconstruct the codeword.")

    return bytes(output)


def frame_payload(payload, capacity):
    """Length + payload + CRC32, padded to the selected data capacity."""
    if len(payload) > 65535:
        raise ValueError("Payload cannot exceed 65535 bytes.")

    framed = (
        struct.pack(">H", len(payload))
        + payload
        + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)
    )

    if len(framed) > capacity:
        raise ValueError("Payload does not fit.")

    return framed.ljust(capacity, b"\x00")


def choose_size(payload_length, bpp):
    """Choose the smallest supported grid with enough encoded capacity."""
    required_data_bytes = payload_length + 6

    for size in range(MIN_SIZE, MAX_SIZE + 1, SIZE_STEP):
        _, positions = build_layout(size)
        total_bytes = len(positions) * bpp // 8

        if calculate_capacity(total_bytes) >= required_data_bytes:
            return size

    raise ValueError(
        f"Payload is too large for bpp={bpp} and the maximum "
        f"{MAX_SIZE}x{MAX_SIZE} grid."
    )


def render_grid(grid, palette, output_path, scale):
    size = len(grid)
    total = size + 2 * QUIET_ZONE
    image = Image.new(
        "RGB",
        (total * scale, total * scale),
        palette[1],
    )
    draw = ImageDraw.Draw(image)

    for y, row in enumerate(grid):
        for x, symbol in enumerate(row):
            left = (x + QUIET_ZONE) * scale
            top = (y + QUIET_ZONE) * scale
            draw.rectangle(
                (left, top, left + scale - 1, top + scale - 1),
                fill=palette[symbol],
            )

    image.save(output_path)


def generate(text, output_path="colorcode.png", bpp=2, scale=DEFAULT_SCALE):
    if scale < 1:
        raise ValueError("Scale must be at least 1.")

    palette = make_palette(bpp)
    payload = text.encode("utf-8")
    size = choose_size(len(payload), bpp)

    grid, positions = build_layout(size)
    total_bytes = len(positions) * bpp // 8
    capacity = calculate_capacity(total_bytes)

    framed = frame_payload(payload, capacity)
    codeword = rs_encode(framed)
    symbols = bytes_to_symbols(codeword, bpp)

    if len(symbols) > len(positions):
        raise RuntimeError("Encoded data exceeds the grid capacity.")

    symbols.extend([0] * (len(positions) - len(symbols)))

    for (x, y), symbol in zip(positions, symbols):
        grid[y][x] = symbol

    render_grid(grid, palette, output_path, scale)

    print(f"Saved: {output_path}")
    print(f"Mode: {bpp}bpp ({1 << bpp} colors)")
    print(f"Grid: {size} x {size}")
    print(f"Payload: {len(payload)} bytes")
    print(f"Data block: {capacity} bytes")
    print(f"ECC: {ECC_BYTES} bytes per RS block")
    print(f"Image: {(size + 2 * QUIET_ZONE) * scale} x "
          f"{(size + 2 * QUIET_ZONE) * scale} pixels")


def nearest_color(rgb, palette):
    """Classify a sampled pixel by nearest RGB palette entry."""
    r, g, b = (int(c) for c in rgb)
    return min(
        range(len(palette)),
        key=lambda i: (
            (r - palette[i][0]) ** 2
            + (g - palette[i][1]) ** 2
            + (b - palette[i][2]) ** 2
        ),
    )


def try_decode(image, size, bpp):
    total_modules = size + 2 * QUIET_ZONE

    if image.width % total_modules:
        raise ValueError("Incompatible dimensions.")

    scale = image.width // total_modules
    if scale < 1 or image.height != image.width:
        raise ValueError("Expected a square image.")

    palette = make_palette(bpp)
    _, positions = build_layout(size)

    total_bytes = len(positions) * bpp // 8
    capacity = calculate_capacity(total_bytes)
    block_count = max(1, math.ceil(capacity / MAX_DATA_PER_BLOCK))
    codeword_length = capacity + block_count * ECC_BYTES
    symbol_count = math.ceil(codeword_length * 8 / bpp)

    if symbol_count > len(positions):
        raise ValueError("Encoded stream exceeds available modules.")

    symbols = []
    for x, y in positions[:symbol_count]:
        px = (x + QUIET_ZONE) * scale + scale // 2
        py = (y + QUIET_ZONE) * scale + scale // 2
        symbols.append(nearest_color(image.getpixel((px, py)), palette))

    codeword = symbols_to_bytes(symbols, bpp, codeword_length)
    data = rs_decode(codeword, capacity)

    payload_length = struct.unpack(">H", data[:2])[0]
    end = 2 + payload_length

    if end + 4 > len(data):
        raise ValueError("Invalid payload length.")

    payload = data[2:end]
    stored_crc = struct.unpack(">I", data[end:end + 4])[0]
    actual_crc = zlib.crc32(payload) & 0xFFFFFFFF

    if stored_crc != actual_crc:
        raise ValueError("CRC mismatch.")

    return payload.decode("utf-8")


def decode_image(path, bpp=None):
    """Try candidate grid sizes and bpp values until validation succeeds."""
    original = Image.open(path).convert("RGB")

    if original.width != original.height:
        raise ValueError("Expected a square image.")

    bpp_values = [bpp] if bpp is not None else list(range(1, 9))
    errors = []

    # Try the four right-angle orientations.
    for rotation in range(4):
        image = original.rotate(90 * rotation, expand=False) if rotation else original

        for size in range(MIN_SIZE, MAX_SIZE + 1, SIZE_STEP):
            total_modules = size + 2 * QUIET_ZONE
            if image.width % total_modules:
                continue

            for bits in bpp_values:
                try:
                    return try_decode(image, size, bits)
                except Exception as exc:
                    errors.append(f"{size}/{bits}bpp: {exc}")

    raise ValueError(
        "Could not decode this image. It may be damaged, distorted, "
        "or use an unsupported format. " + "; ".join(errors[-3:])
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Configurable multicolor code with Reed-Solomon ECC"
    )

    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--encode", metavar="TEXT")
    mode.add_argument("--decode", metavar="PNG")

    parser.add_argument("-o", "--output", default="colorcode.png")
    parser.add_argument("--bpp", type=int, default=2)
    parser.add_argument("--scale", type=int, default=DEFAULT_SCALE)
    args = parser.parse_args()

    try:
        if args.encode is not None:
            generate(args.encode, args.output, args.bpp, args.scale)
        else:
            if not 1 <= args.bpp <= 8:
                parser.error("--bpp must be between 1 and 8.")

            # On decode, omit --bpp to try all supported values.
            decoded = decode_image(
                args.decode,
                bpp=args.bpp if "--bpp" in __import__("sys").argv else None,
            )
            print(decoded)

    except (ValueError, OSError) as exc:
        parser.error(str(exc))
