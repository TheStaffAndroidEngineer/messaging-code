"""Threadline: durable message identity, ordered change events and explicit resync."""

from urllib.parse import urlsplit, parse_qs
from base import BaseService, Problem, fields, integer, text, run


class Service(BaseService):
    KIND = "threadline"

    def seed(self):
        return {
            "epoch": 1,
            "active": True,
            "sequence": 2,
            "messages": [
                {
                    "id": "welcome-1",
                    "author": "Morgan",
                    "text": "The studio door is open. Meet by the north entrance.",
                    "sequence": 1,
                    "deleted": False,
                },
                {
                    "id": "welcome-2",
                    "author": "You",
                    "text": "On my way. I will bring the layout notes.",
                    "sequence": 2,
                    "deleted": False,
                },
            ],
            "events": [
                {"event": 1, "kind": "upsert", "messageId": "welcome-1"},
                {"event": 2, "kind": "upsert", "messageId": "welcome-2"},
            ],
            "floor": 0,
        }

    def view(self, state, now):
        return {
            "room": "Studio team",
            "epoch": state["epoch"],
            "active": state["active"],
            "lastEvent": state["sequence"],
            "messageCount": sum(not m["deleted"] for m in state["messages"]),
        }

    def sync(self, state, cursor):
        if not state["active"]:
            return {
                "epoch": state["epoch"],
                "active": False,
                "reset": True,
                "messages": [],
                "events": [],
                "cursor": state["sequence"],
                "more": False,
            }
        if cursor < state["floor"] or cursor > state["sequence"]:
            return {
                "epoch": state["epoch"],
                "active": True,
                "reset": True,
                "messages": [m for m in state["messages"] if not m["deleted"]],
                "events": [],
                "cursor": state["sequence"],
                "more": False,
            }
        events = []
        for event in state["events"]:
            if event["event"] > cursor:
                message = next(m for m in state["messages"] if m["id"] == event["messageId"])
                events.append({**event, "message": message})
                if len(events) == 4:
                    break
        next_cursor = events[-1]["event"] if events else cursor
        return {
            "epoch": state["epoch"],
            "active": True,
            "reset": False,
            "messages": [],
            "events": events,
            "cursor": next_cursor,
            "more": next_cursor < state["sequence"],
        }

    def route(self, method, path, body):
        url = urlsplit(path)
        if method == "GET" and url.path == "/sync":
            query = parse_qs(url.query, keep_blank_values=True)
            fields(query, {"after"})
            if len(query["after"]) != 1 or not query["after"][0].isdigit():
                raise Problem("Invalid cursor")
            cursor = integer(int(query["after"][0]))
            with self.transaction() as db:
                import json

                controls = self.controls(db)
                if controls.get("unavailable"):
                    raise Problem("Fixture unavailable", 503)
                state = json.loads(db.execute("SELECT value FROM state WHERE id=1").fetchone()[0])
                return (
                    200,
                    {
                        "kind": self.KIND,
                        "observedAt": self.now(controls),
                        "data": self.sync(state, cursor),
                    },
                    False,
                )
        return super().route(method, path, body)

    def event(self, state, kind, message_id):
        state["sequence"] += 1
        state["events"].append({"event": state["sequence"], "kind": kind, "messageId": message_id})
        if len(state["events"]) > 8:
            removed = state["events"].pop(0)
            state["floor"] = removed["event"]

    def apply(self, state, action, args, now):
        if action in {"send", "incoming"}:
            fields(args, {"epoch", "messageId", "text"})
            epoch = integer(args["epoch"], 1)
            message_id = text(args["messageId"], 80)
            body = text(args["text"], 500)
            if not state["active"] or epoch != state["epoch"]:
                return {
                    "accepted": False,
                    "reason": "Membership changed; queued message not admitted",
                }
            previous = next((m for m in state["messages"] if m["id"] == message_id), None)
            author = "You" if action == "send" else "Morgan"
            if previous:
                if previous["text"] != body or previous["author"] != author:
                    raise Problem("Message identity changed")
                return {"accepted": True, "messageId": message_id, "sequence": previous["sequence"]}
            if len(state["messages"]) >= 100:
                return {"accepted": False, "reason": "Fixture history is full"}
            self.event(state, "upsert", message_id)
            message = {
                "id": message_id,
                "author": author,
                "text": body,
                "sequence": state["sequence"],
                "deleted": False,
            }
            state["messages"].append(message)
            return {"accepted": True, "messageId": message_id, "sequence": state["sequence"]}
        if action == "delete":
            fields(args, {"epoch", "messageId"})
            integer(args["epoch"], 1)
            text(args["messageId"], 80)
            if not state["active"] or args["epoch"] != state["epoch"]:
                return {"accepted": False, "reason": "Membership changed"}
            message = next((m for m in state["messages"] if m["id"] == args["messageId"]), None)
            if not message or message["author"] != "You":
                return {"accepted": False, "reason": "No deletable message"}
            if not message["deleted"]:
                message["deleted"] = True
                self.event(state, "delete", message["id"])
            return {"accepted": True, "messageId": message["id"]}
        if action in {"leave", "rejoin"}:
            fields(args, {"epoch"})
            integer(args["epoch"], 1)
            if args["epoch"] != state["epoch"]:
                return {"accepted": False, "reason": "Stale membership command"}
            desired = action == "rejoin"
            if state["active"] == desired:
                return {"accepted": True, "epoch": state["epoch"]}
            state["epoch"] += 1
            state["active"] = desired
            return {"accepted": True, "epoch": state["epoch"]}
        raise Problem("Unknown action")


if __name__ == "__main__":
    run(Service, 8915)
