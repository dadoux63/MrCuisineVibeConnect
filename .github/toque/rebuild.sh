#!/usr/bin/env bash
# Reconstruit la version combinée Toque (domotique + Cookomix + Jow) sur la dernière version
# publiée par Wuki60, sans rien installer ni publier.
#
# Méthode : branche neuve depuis le tag amont, fusion (merge 3 voies) de la dernière branche
# toque-combo-NNN. Git ne rejoue que les ajouts Toque faits depuis le tag de base de cette branche.
# Seul conflit résolu automatiquement : les lignes versionCode / versionName de app/build.gradle.kts
# (l'amont les change à chaque version). Tout autre conflit arrête la reconstruction.
#
# Variables d'entrée (toutes facultatives) :
#   UPSTREAM_URL   dépôt amont (défaut : Wuki60/MrCuisineVibeConnect)
#   TARGET_TAG     tag amont à utiliser (défaut : le plus récent vX.Y.Z)
#   SOURCE_BRANCH  branche Toque à reporter (défaut : la toque-combo-* de base amont la plus récente, puis de versionCode le plus haut)
#   WORKDIR        dossier de travail (défaut : dossier courant, un clone du fork)
#   OUT            fichier de sortie clé=valeur (défaut : $GITHUB_OUTPUT ou /dev/stdout)
#   REPORT         fichier du compte rendu en Markdown (défaut : rapport.md)
#   DRY_RUN=true   essai : branche toque-combo-NNN-essai, jamais poussée ; la branche source peut
#                  être sur le même tag (recompile alors la version combinée actuelle)
#
# Sorties : status = up-to-date | ready | conflict | error, puis branch, tag, source,
# version_name, version_code, commit.
set -euo pipefail

UPSTREAM_URL=${UPSTREAM_URL:-https://github.com/Wuki60/MrCuisineVibeConnect.git}
OUT=${OUT:-${GITHUB_OUTPUT:-/dev/stdout}}
REPORT=${REPORT:-$PWD/rapport.md}
GRADLE=app/build.gradle.kts
cd "${WORKDIR:-.}"

out() { echo "$1=$2" >> "$OUT"; echo "  $1=$2" >&2; }
fail() { out status "$1"; { echo; echo "$2"; } >> "$REPORT"; echo "ARRÊT ($1) : $2" >&2; exit 0; }

: > "$REPORT"
git config user.name  >/dev/null 2>&1 || git config user.name  "Toque (reconstruction automatique)"
git config user.email >/dev/null 2>&1 || git config user.email "toque-bot@users.noreply.github.com"

# 1. Versions amont et branches Toque existantes.
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
git fetch --quiet --no-tags upstream '+refs/tags/v*:refs/tags/v*'
git fetch --quiet origin '+refs/heads/toque-combo-*:refs/remotes/origin/toque-combo-*'

tag=${TARGET_TAG:-$(git tag -l 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)}
git rev-parse -q --verify "refs/tags/$tag^{commit}" >/dev/null || fail error "Tag amont introuvable : $tag"
ver=${tag#v}
# Nom sans ambiguïté avec les branches faites à la main (ex. toque-combo-091 basée sur v0.9.0) :
# toque-combo-v0.9.1 désigne toujours le tag amont v0.9.1.
branch="toque-combo-v$ver"
dry=${DRY_RUN:-false}
[ "$dry" = true ] && branch="$branch-essai"
out tag "$tag"
out branch "$branch"

ver_lt() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" = "$1" ]; }
base_of() { git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' "$1" 2>/dev/null | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true; }
read_gradle() { git show "$1:$GRADLE" | grep -oP "$2"'\s*=\s*"?\K[^"\s]+' | head -1; }

# Branches Toque connues (toutes les toque-combo-*, sauf les essais), avec leur tag amont de base
# (déduit de l'historique, pas du nom) et leur versionCode : « base versionCode branche ».
candidates=$(for b in $(git for-each-ref --format='%(refname:short)' 'refs/remotes/origin/toque-combo-*' | grep -v -- '-essai$' || true); do
               bt=$(base_of "$b"); vc=$(read_gradle "$b" versionCode 2>/dev/null || true)
               if [ -n "$bt" ] && [ -n "$vc" ]; then echo "${bt#v} $vc $b"; fi
             done)

if [ "$dry" != true ]; then
  done_on=$(echo "$candidates" | awk -v v="$ver" '$1 == v {print $3}' | head -1)
  if [ -n "$done_on" ] || git rev-parse -q --verify "refs/remotes/origin/$branch" >/dev/null; then
    out status up-to-date
    echo "$tag est déjà repris dans ${done_on:-$branch} : rien à faire." >&2
    exit 0
  fi
fi

# Branche source : base amont la plus récente (antérieure à $tag), puis versionCode le plus haut.
if [ -n "${SOURCE_BRANCH:-}" ]; then
  source="origin/${SOURCE_BRANCH#origin/}"
  git rev-parse -q --verify "$source" >/dev/null || fail error "Branche source introuvable : $source"
else
  source=$(echo "$candidates" | while read -r bv vc b; do
             [ -n "$b" ] || continue
             if ver_lt "$bv" "$ver" || { [ "$dry" = true ] && [ "$bv" = "$ver" ]; }; then echo "$bv $vc $b"; fi
           done | sort -k1,1V -k2,2n | tail -1 | cut -d' ' -f3)
  [ -n "$source" ] || fail error "Aucune branche toque-combo-* antérieure à $tag."
fi
base=$(base_of "$source")
[ -n "$base" ] || fail error "Tag amont de base introuvable pour $source."
git merge-base --is-ancestor "$base" "$tag" || fail error "$base n'est pas un ancêtre de $tag : l'historique amont a été réécrit."
out source "${source#origin/}"

# 2. Numéros de version : versionCode strictement croissant (au-dessus de l'installé et de l'amont).
prev_code=$(read_gradle "$source" versionCode)
prev_name=$(read_gradle "$source" versionName)
up_code=$(read_gradle "$tag" versionCode)
base_ver=${base#v}
suffix=${prev_name#"$base_ver"}
[ "$suffix" != "$prev_name" ] || suffix="-ha-ckx-jow"
code=$(( (prev_code > up_code ? prev_code : up_code) + 1 ))
name="$ver$suffix"
[ "$dry" = true ] && name="$name-essai"
out version_code "$code"
out version_name "$name"

{
  echo "- Amont : \`$tag\` (versionCode amont $up_code)"
  echo "- Ajouts Toque repris de : \`${source#origin/}\` (base \`$base\`, $prev_name, versionCode $prev_code)"
  echo "- Nouvelle version : **$name**, versionCode **$code**, branche \`$branch\`"
  echo
  echo "<details><summary>Changements amont $base → $tag</summary>"
  echo
  git log --no-merges -n 80 --pretty='- %h %s' "$base..$tag"
  echo
  echo "</details>"
} >> "$REPORT"

# 3. Fusion.
git checkout --quiet -B "$branch" "$tag"
if ! git merge --no-ff --no-commit "$source" >/dev/null 2>&1; then
  conflicts=$(git diff --name-only --diff-filter=U)
  [ -n "$conflicts" ] || { git merge --abort || true; fail conflict "La fusion a échoué sans conflit de fichier listé."; }
  others=$(echo "$conflicts" | grep -vxF "$GRADLE" || true)
  if [ -n "$others" ] || ! python3 -I "$(dirname "$0")/resolve_version_conflict.py" "$GRADLE"; then
    {
      echo "### Conflits non résolus automatiquement"
      echo; echo "$conflicts" | sed 's/^/- `/; s/$/`/'; echo
      echo '```diff'; git diff > "$REPORT.diff" || true; head -c 20000 "$REPORT.diff"; echo '```'
    } >> "$REPORT"
    git merge --abort || true
    fail conflict "Conflit hors numéros de version : reconstruction arrêtée, rien n'a été poussé."
  fi
  git add "$GRADLE"
fi

# 4. Numéros de version, puis commit de fusion.
sed -i -E "s/^(\s*versionCode\s*=\s*)[0-9]+/\1$code/; s/^(\s*versionName\s*=\s*)\"[^\"]*\"/\1\"$name\"/" "$GRADLE"
[ "$(grep -cE "versionCode\s*=\s*$code\b" "$GRADLE")" = 1 ] || fail error "versionCode non écrit dans $GRADLE."
[ "$(grep -cF "versionName = \"$name\"" "$GRADLE")" = 1 ] || fail error "versionName non écrit dans $GRADLE."
if git grep -nE '^(<<<<<<<|>>>>>>>)( |$)' -- . >/dev/null; then
  fail error "Marqueurs de conflit restants."
fi
git add "$GRADLE"
git commit --quiet -m "Toque $ver + domotique + Cookomix + Jow : version $name (versionCode $code)" \
  -m "Reconstruction automatique : $tag (Wuki60) fusionné avec ${source#origin/}."
out commit "$(git rev-parse HEAD)"
out status ready
