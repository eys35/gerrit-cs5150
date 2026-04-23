import pytest

from gerrit.module_mapping import (
    path_to_module_id,
    paths_to_module_ids,
    qualified_module_id,
)


@pytest.mark.parametrize(
    "path,expected",
    [
        ("/COMMIT_MSG", None),
        ("/MERGE_LIST", None),
        ("/PATCHSET_LEVEL", None),
        ("", None),
        ("  ", None),
        ("/java/com/Foo.java", "java"),
        ("java/com/Foo.java", "java"),
        ("polygerrit-ui/app/x.ts", "polygerrit-ui"),
        ("README.md", "README.md"),
        ("/LICENSE", "LICENSE"),
        ("/", None),
        ("//double", "double"),
    ],
)
def test_path_to_module_id(path, expected):
    assert path_to_module_id(path) == expected


def test_path_to_module_id_empty_first_segment_uses_root():
    assert path_to_module_id("/foo//bar") == "foo"


def test_paths_to_module_ids_dedupes_and_skips():
    assert paths_to_module_ids(
        ["/a/x", "/a/y", "/b/z", "/COMMIT_MSG", "/a/w"]
    ) == frozenset({"a", "b"})


def test_qualified_module_id():
    assert (
        qualified_module_id("plugins/foo", "/java/Bar.java") == "plugins/foo:java"
    )
    assert qualified_module_id("plugins/foo", "/COMMIT_MSG") is None