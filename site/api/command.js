import { readFile, writeFile, body, noStore } from "./_store.js";

export default async function handler(req, res) {
  noStore(res);
  try {
    if (req.method === "GET") {
      const command = await readFile("command.json");
      // comando velho (mais de 5 min) nao interessa mais
      if (command && command.created && Date.now() - command.created > 300_000) {
        res.status(200).json(null);
        return;
      }
      res.status(200).json(command || null);
      return;
    }
    if (req.method === "POST") {
      const b = body(req);
      if (b.ack) {
        const command = await readFile("command.json");
        if (!command || command.id === b.ack) {
          await writeFile("command.json", {});
        }
        res.status(200).json({ ok: true });
        return;
      }
      const action = String(b.action || "");
      if (!["track", "stop", "where"].includes(action)) {
        res.status(400).json({ error: "action" });
        return;
      }
      const command = {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        action,
        nick: String(b.nick || ""),
        created: Date.now(),
      };
      await writeFile("command.json", command);
      res.status(200).json({ ok: true, command });
      return;
    }
    res.status(405).json({ error: "method" });
  } catch (e) {
    res.status(500).json({ error: String((e && e.message) || e) });
  }
}
