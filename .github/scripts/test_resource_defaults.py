import tempfile
import unittest
from pathlib import Path
from check_resource_defaults import find_missing_defaults


class ResourceDefaultsTest(unittest.TestCase):
    def test_translation_without_default_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "app/src/main/res/values-zh/strings.xml"
            source.parent.mkdir(parents=True)
            source.write_text('<resources><string name="missing">翻译</string></resources>')
            self.assertEqual([(str(source.relative_to(root)), "string", "missing")], find_missing_defaults(root))

    def test_defaults_can_be_split_across_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            res = root / "app/src/main/res"
            (res / "values").mkdir(parents=True)
            (res / "values-zh").mkdir()
            (res / "values/other.xml").write_text('<resources><string name="present">Default</string></resources>')
            (res / "values-zh/strings.xml").write_text('<resources><string name="present">翻译</string></resources>')
            self.assertEqual([], find_missing_defaults(root))

    def test_resource_type_must_match_and_styles_do_not_require_fallbacks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            res = root / "ui/src/main/res"
            (res / "values").mkdir(parents=True)
            (res / "values-zh").mkdir()
            (res / "values/strings.xml").write_text('<resources><string name="count">Count</string></resources>')
            (res / "values-zh/strings.xml").write_text('<resources><plurals name="count"><item quantity="other">数量</item></plurals><style name="NightOnly"/></resources>')
            self.assertEqual([(str(Path("ui/src/main/res/values-zh/strings.xml")), "plurals", "count")],
                             find_missing_defaults(root))
