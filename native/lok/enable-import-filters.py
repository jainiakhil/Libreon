#!/usr/bin/env python3
# Registers the Apple iWork import filters (Pages, Numbers, Keynote) in a static (Android)
# engine build.
#
# libetonyek is already built, but solenv/bin/native-code.py only lists the filters
# Collabora ships, so the linker drops it. Listing the constructors keeps it, and the same
# list limits services.rdb. (WordPerfect, Works/Lotus, AbiWord, eBook and StarOffice imports
# were tried the same way: they link and register but fail to load documents on Android, so
# they are left out.)
#
# Usage (from the engine tree; idempotent): python3 enable-import-filters.py solenv/bin/native-code.py
import sys

TAG = "  # offline-office"

FILTERS = {
    "writer_constructor_list": ["org_libreoffice_comp_Writer_PagesImportFilter_get_implementation"],
    "calc_constructor_list": ["org_libreoffice_comp_Calc_NumbersImportFilter_get_implementation"],
    "draw_constructor_list": ["org_libreoffice_comp_Impress_KeynoteImportFilter_get_implementation"],
}

path = sys.argv[1]
# Drop anything a previous run added, then add the current list.
lines = [l for l in open(path, encoding="utf-8").read().split("\n") if not l.endswith(TAG)]

out, current = [], None
for line in lines:
    stripped = line.strip()
    if stripped.endswith("= [") and stripped.split(" ")[0] in FILTERS:
        current = stripped.split(" ")[0]
    elif current and stripped == "]":
        out.extend(f'    "{name}",{TAG}' for name in FILTERS[current])
        current = None
    out.append(line)

open(path, "w", encoding="utf-8").write("\n".join(out))
print(f"enabled {sum(map(len, FILTERS.values()))} import filters in {path}")
