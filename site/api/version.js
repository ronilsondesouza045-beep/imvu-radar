export default async function handler(req, res) {
  try {
    const r = await fetch(
      "https://api.github.com/repos/ronilsondesouza045-beep/imvu-radar/releases/latest",
      { headers: { "User-Agent": "imvu-radar-site" } }
    );
    if (!r.ok) {
      res.status(200).json({ ok: false, error: "HTTP " + r.status });
      return;
    }
    const j = await r.json();
    const assets = (j.assets || []).map((a) => ({
      name: a.name,
      url: a.browser_download_url,
      size: a.size,
    }));
    res.status(200).json({ ok: true, tag: j.tag_name, assets });
  } catch (e) {
    res.status(200).json({ ok: false, error: String((e && e.message) || e) });
  }
}
