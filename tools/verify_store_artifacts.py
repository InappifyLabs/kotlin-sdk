"""Verify store separation after assembleRelease + generateMetadataFileForReleasePublication."""
import io
import json
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / "sdk/build"
metadata = json.loads((root / "publications/release/module.json").read_text())
attribute = "com.android.build.api.attributes.ProductFlavor:inappifyStore"
for store in ("allStores", "myket", "bazaar", "direct"):
    has_myket = store in ("allStores", "myket")
    has_bazaar = store in ("allStores", "bazaar")
    variants = [v for v in metadata["variants"] if v.get("attributes", {}).get(attribute) == store
                and v["attributes"].get("org.gradle.usage") == "java-runtime"
                and v["attributes"].get("org.gradle.category") == "library"]
    assert len(variants) == 1, (store, "missing/ambiguous runtime metadata")
    poolakey = any(d["group"] == "com.github.cafebazaar.Poolakey" and d["module"] == "poolakey"
                   for d in variants[0].get("dependencies", []))
    assert poolakey == has_bazaar, (store, "incorrect Poolakey dependency")
    with ZipFile(root / f"outputs/aar/sdk-{store}-release.aar") as aar:
        manifest = ET.fromstring(aar.read("AndroidManifest.xml"))
        ns = "{http://schemas.android.com/apk/res/android}"
        permissions = {p.attrib[ns + "name"] for p in manifest.findall("uses-permission")}
        assert ("ir.mservices.market.BILLING" in permissions) == has_myket, store
        components = [e.attrib.get(ns + "name", "") for e in manifest.iter()]
        assert any("billing.myket" in c for c in components) == has_myket, store
        assert ("ir.mservices.market" in components) == has_myket, store
        with ZipFile(io.BytesIO(aar.read("classes.jar"))) as classes:
            names = classes.namelist()
            assert any(n.endswith("BazaarStoreBillingAdapter.class") for n in names) == has_bazaar, store
            assert any(n.endswith("MyketStoreBillingAdapter.class") for n in names) == has_myket, store
            assert any("/billing/myket/" in n for n in names) == has_myket, store
        assert ("res/values/values.xml" in aar.namelist()) == has_myket, store
    print(f"{store}: AAR classes, resources, manifest and published dependencies verified")
