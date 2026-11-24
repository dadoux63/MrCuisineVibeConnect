"""Résout un conflit de fusion dans app/build.gradle.kts, uniquement s'il porte sur les numéros de version.

Chaque bloc de conflit doit ne contenir, des deux côtés, que des lignes versionCode / versionName
(ou des lignes vides). Le côté amont (« ours ») est gardé ; rebuild.sh écrit ensuite les nouveaux
numéros. Si un bloc contient autre chose, le fichier n'est pas modifié et le code de sortie vaut 1.
"""
import re
import sys

ALLOWED = re.compile(r"^\s*(versionCode\s*=\s*\d+|versionName\s*=\s*\"[^\"]*\")?\s*$")


def resolve(text):
    out, side, ours, theirs, blocks = [], None, [], [], 0
    for line in text.splitlines(keepends=True):
        if line.startswith("<<<<<<< "):
            if side is not None:
                return None
            side, ours, theirs = "ours", [], []
        elif side is not None and line.startswith("||||||| "):
            side = "base"
        elif side is not None and line.rstrip("\n") == "=======":
            side = "theirs"
        elif side is not None and line.startswith(">>>>>>> "):
            if not all(ALLOWED.match(l) for l in ours + theirs):
                return None
            out.extend(ours)
            side, blocks = None, blocks + 1
        elif side == "ours":
            ours.append(line)
        elif side == "theirs":
            theirs.append(line)
        elif side is None:
            out.append(line)
    if side is not None or blocks == 0:
        return None
    return "".join(out)


def main(path):
    with open(path, encoding="utf-8") as f:
        result = resolve(f.read())
    if result is None:
        print(f"{path} : conflit hors numéros de version, non résolu.", file=sys.stderr)
        return 1
    with open(path, "w", encoding="utf-8") as f:
        f.write(result)
    print(f"{path} : conflit de numéros de version résolu (côté amont gardé).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
