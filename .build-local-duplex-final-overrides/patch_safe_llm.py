from pathlib import Path
import sys

root = Path(sys.argv[1])
p = root / "app/src/main/cpp/LlamaBridge.cpp"
s = p.read_text(encoding="utf-8")

# Keep the safe cold-start wins: explicit mmap and smaller context/batches.
# Restore default extra CPU buffers / repack path because disabling it can
# break or severely degrade quantized CPU execution on Android backends.
s = s.replace(
'''    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.lazy_mode = LLAMA_LAZY_MODE_AUTO;
    mp.use_extra_bufts = false;
''',
'''    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.lazy_mode = LLAMA_LAZY_MODE_AUTO;
'''
)

p.write_text(s, encoding="utf-8")
print("safe LLM runtime patch applied")
