#!/usr/bin/env python3
"""Create original spoken demo audio on macOS (requires say and ffmpeg)."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CHAPTERS = {
    "Chapter 1": """The Lantern Keeper. Chapter one. The light at the edge of town.
Every evening, Mara walked the narrow path above the harbour. She carried a brass lantern, though the lighthouse had an electric lamp and the path was easy to see. The lantern had belonged to her grandfather. He had told her that a light was useful even when you did not need it yourself.
That evening, the sea was green and still. A little boat waited beyond the rocks. Its sail was folded, and no one stood at the wheel. Mara stopped. Three times, she raised the lantern. Three times, a small light answered from the boat.
She set down her bag and ran toward the harbour. Behind her, the lighthouse swept its great beam across the water. The little answering light had gone out.""",
    "Chapter 2": """Chapter two. Across the water.
The harbour master was closing his office when Mara arrived. She explained what she had seen. He looked out at the calm sea, then reached for his coat.
They took the old launch through the channel. Mara held her lantern low so that its glow would not spoil their view of the dark. As they drew closer, they could hear someone calling. A young sailor sat beside the broken mast, wrapped in a blue blanket. His radio was silent. His engine would not start.
I thought nobody could see me, he said. Mara put the lantern beside him. Someone always checks the water, she replied. Together, they fastened the tow rope. The harbour lights grew brighter as the launch turned for home.""",
    "Chapter 10": """Chapter ten. A place to return to.
In the morning, the little boat rested safely beside the quay. The sailor had slept at the inn and borrowed tools to repair his engine. Before leaving, he found Mara outside the lighthouse.
He gave her a small drawing of the harbour, made on the back of an old chart. In the corner was a tiny golden light. This is what I saw first, he told her.
Mara pinned the drawing above her grandfather's desk. That evening, she polished the brass lantern and filled it again. The path was clear, and the lighthouse was working. She took the lantern anyway.
The end. This is an original demonstration recording created for Hearth.""",
}

def main() -> None:
    destination = ROOT / "demo" / "The Lantern Keeper"
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "cover.png").write_bytes((ROOT / "demo" / "lantern-keeper-cover.png").read_bytes())
    with tempfile.TemporaryDirectory(prefix="hearth-narration-") as temporary:
        for chapter, text in CHAPTERS.items():
            source = Path(temporary) / "chapter.txt"
            source.write_text(text)
            audio = Path(temporary) / "chapter.aiff"
            subprocess.run(["say", "-v", "Samantha", "-r", "145", "-f", str(source), "-o", str(audio)], check=True)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(audio), "-c:a", "libmp3lame", "-q:a", "4", "-metadata", "artist=Hearth Studio", "-metadata", "album=The Lantern Keeper", str(destination / f"{chapter}.mp3")], check=True)
    print(destination)

if __name__ == "__main__":
    main()
