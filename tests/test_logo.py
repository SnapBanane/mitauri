import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).parent.parent
SVG = ROOT / "assets" / "mitauri-logo.svg"
DRAWABLE = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable" / "logo_mitauri.xml"
SVG_NS = "{http://www.w3.org/2000/svg}"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def svg_shapes():
    return [
        (
            path.get("d"),
            None if path.get("fill") == "none" else path.get("fill"),
            path.get("stroke"),
            path.get("stroke-width"),
        )
        for path in ET.parse(SVG).getroot().iter(f"{SVG_NS}path")
    ]


def drawable_shapes():
    return [
        (
            path.get(f"{ANDROID_NS}pathData"),
            path.get(f"{ANDROID_NS}fillColor"),
            path.get(f"{ANDROID_NS}strokeColor"),
            path.get(f"{ANDROID_NS}strokeWidth"),
        )
        for path in ET.parse(DRAWABLE).getroot().iter("path")
    ]


def test_app_logo_matches_the_svg():
    assert svg_shapes()
    assert svg_shapes() == drawable_shapes()


def test_svg_loads_nothing_from_outside():
    allowed = {f"{SVG_NS}svg", f"{SVG_NS}title", f"{SVG_NS}path"}
    for element in ET.parse(SVG).getroot().iter():
        assert element.tag in allowed
        assert not any(name.endswith("href") for name in element.attrib)
