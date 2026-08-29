#!/usr/bin/env python3
"""
build_radar_db.py - Gera a base de radares do RadarGuard a partir do OpenStreetMap.

Baixa todos os nos highway=speed_camera do Brasil via Overpass API (em blocos,
com rotacao de mirrors e retry), normaliza e grava:

    app/src/main/assets/radares.csv.gz   <- base embarcada no APK
    app/src/main/assets/radares.meta.json

Formato do CSV (sem cabecalho, para parsing rapido no Android):
    lat;lon;maxspeed;direction;label

    lat, lon   float com 6 casas (~11 cm de precisao)
    maxspeed   int km/h, 0 = desconhecido
    direction  int graus 0-359 do sentido do fluxo medido, -1 = desconhecido
    label      texto curto para a UI (ex.: "BR-470 km 192.0"), pode ser vazio

Uso:
    python tools/build_radar_db.py
    python tools/build_radar_db.py --bbox -33.8,-57.7,-27.0,-49.6
"""

import argparse
import gzip
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.osm.jp/api/interpreter",
]

# bbox do Brasil: (sul, oeste, norte, leste)
BRASIL = (-34.0, -74.1, 5.5, -34.0)

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "assets"))


def log(msg):
    sys.stderr.write(msg + "\n")
    sys.stderr.flush()


class RateLimited(Exception):
    """O servidor recusou por excesso de requisicoes nossas. Esperar resolve."""


class AreaTooBig(Exception):
    """O servidor nao deu conta desta area. Dividir resolve."""


# O Overpass publico da 429 se apertarmos. Um intervalo minimo global entre
# requisicoes evita o 429 em vez de remediar depois.
_last_request = [0.0]
MIN_INTERVAL = 3.0


def _throttle():
    gap = time.time() - _last_request[0]
    if gap < MIN_INTERVAL:
        time.sleep(MIN_INTERVAL - gap)
    _last_request[0] = time.time()


def overpass(query, tries=2, timeout=300):
    """
    Executa uma query Overpass rodando entre os mirrors.

    Distingue os dois motivos de falha, porque a reacao certa e oposta em cada um:
    429 pede paciencia (dividir a area so multiplicaria as requisicoes e pioraria),
    enquanto timeout/erro do servidor pede uma area menor.
    """
    last = "sem tentativa"
    saw_rate_limit = False
    saw_failure = False

    for attempt in range(tries):
        for mirror in MIRRORS:
            host = mirror.split("/")[2]
            _throttle()
            try:
                req = urllib.request.Request(
                    mirror,
                    data=urllib.parse.urlencode({"data": query}).encode(),
                    headers={"User-Agent": "RadarGuard/1.0 (base pessoal de radares)"},
                )
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    raw = resp.read().decode("utf-8", "replace")
                if raw.lstrip().startswith("{"):
                    return json.loads(raw)
                # Overpass devolve HTML quando estoura o proprio timeout interno
                last = host + ": resposta nao-JSON (servidor ocupado)"
                saw_failure = True
            except urllib.error.HTTPError as exc:
                last = host + ": HTTP " + str(exc.code)
                if exc.code == 429:
                    saw_rate_limit = True
                else:
                    saw_failure = True
            except Exception as exc:
                last = host + ": " + type(exc).__name__
                saw_failure = True
            log("    ~ " + last)
        if attempt + 1 < tries:
            time.sleep(5)

    # Se algum mirror aguentou a area e so outro reclamou de cota, o problema e cota.
    if saw_rate_limit and not saw_failure:
        raise RateLimited(last)
    raise AreaTooBig(last)


def fetch_country(iso):
    """
    Baixa os radares de um pais inteiro numa unica requisicao.

    Este e o caminho preferido. Parece contra-intuitivo pedir tudo de uma vez, mas
    o Overpass limita por NUMERO de requisicoes, nao por volume: quebrar o Brasil em
    dezenas de blocos rende HTTP 429 atras de 429, enquanto uma query so com filtro
    de area volta em menos de um minuto. O filtro de area ainda tem a vantagem de
    respeitar a fronteira, em vez de arrastar radares dos paises vizinhos.
    """
    query = ('[out:json][timeout:800];'
             'area["ISO3166-1"="' + iso + '"][admin_level=2]->.pais;'
             'node["highway"="speed_camera"](area.pais);'
             'out body;')
    data = overpass(query, tries=2, timeout=900)
    return [el for el in data.get("elements", [])
            if el.get("type") == "node" and "lat" in el]


def fetch_area(s, w, n, e, depth=0):
    """
    Baixa os radares de um bbox, quebrando em quatro quando o servidor nao aguenta.

    A densidade varia demais: um bloco sobre o oceano volta em um segundo, o mesmo
    bloco sobre Sao Paulo estoura o timeout do Overpass. Em vez de chutar um
    tamanho fixo que sirva pros dois casos, comeca grande e subdivide so onde precisa.
    """
    query = ('[out:json][timeout:250];'
             'node["highway"="speed_camera"](' + str(s) + "," + str(w) + ","
             + str(n) + "," + str(e) + ');out body;')

    data = None
    for wait_round in range(4):
        try:
            data = overpass(query)
            break
        except RateLimited:
            pause = 45 * (wait_round + 1)
            log("      cota do servidor estourada; esperando " + str(pause) + "s")
            time.sleep(pause)
        except AreaTooBig:
            if depth >= 5 or (n - s) <= 0.4:
                log("      !! desisti de bbox(" + format(s, ".2f") + ","
                    + format(w, ".2f") + "," + format(n, ".2f") + ","
                    + format(e, ".2f") + ") - area pulada")
                return []
            mlat, mlon = (s + n) / 2.0, (w + e) / 2.0
            log("      area pesada demais; dividindo em 4 (nivel "
                + str(depth + 1) + ")")
            out = []
            for sub in ((s, w, mlat, mlon), (s, mlon, mlat, e),
                        (mlat, w, n, mlon), (mlat, mlon, n, e)):
                out.extend(fetch_area(sub[0], sub[1], sub[2], sub[3], depth + 1))
            return out

    if data is None:
        log("      !! cota nao liberou; area pulada")
        return []
    return [el for el in data.get("elements", [])
            if el.get("type") == "node" and "lat" in el]


def tiles(bbox, step=10.0):
    """Divide um bbox grande em blocos menores; o Overpass recusa areas enormes."""
    south, west, north, east = bbox
    lat = south
    while lat < north:
        lon = west
        while lon < east:
            yield (lat, lon, min(lat + step, north), min(lon + step, east))
            lon += step
        lat += step


# maxspeed pode vir "60", "60 km/h", "BR:urban"... so aproveitamos o numero puro
_SPEED_RE = re.compile(r"^\s*(\d{2,3})\s*(?:km/h)?\s*$", re.I)

# direction pode vir em graus ou como ponto cardeal
_CARDINAL = {
    "N": 0, "NNE": 22, "NE": 45, "ENE": 68, "E": 90, "ESE": 113,
    "SE": 135, "SSE": 158, "S": 180, "SSW": 203, "SW": 225, "WSW": 248,
    "W": 270, "WNW": 293, "NW": 315, "NNW": 338,
}


def parse_speed(tags):
    for key in ("maxspeed", "maxspeed:forward", "maxspeed:backward"):
        raw = tags.get(key)
        if not raw:
            continue
        m = _SPEED_RE.match(raw)
        if m:
            v = int(m.group(1))
            if 10 <= v <= 140:
                return v
    return 0


def parse_direction(tags):
    for key in ("direction", "camera:direction"):
        raw = tags.get(key)
        if not raw:
            continue
        raw = raw.strip().upper()
        if raw in _CARDINAL:
            return _CARDINAL[raw]
        try:
            return int(round(float(raw))) % 360
        except ValueError:
            continue
    return -1


def parse_label(tags):
    """Rotulo curto pra UI. 'description' do import Inmetro traz rodovia + km."""
    for key in ("description", "name", "ref"):
        raw = tags.get(key)
        if raw:
            text = " ".join(raw.split())
            # "BR-470 Km 192.050 DECRESCENTE" -> "BR-470 km 192.0"
            m = re.match(r"^([A-Za-z]{2}-\d{3})\s+Km\s+([\d.]+)", text, re.I)
            if m:
                try:
                    return m.group(1).upper() + " km " + format(float(m.group(2)), ".1f")
                except ValueError:
                    pass
            return text[:48]
    return ""


def is_expired(tags, today):
    """note='Validade: 22/09/2026' vem do registro Inmetro. Vencido = provavel descomissionado."""
    m = re.search(r"Validade:\s*(\d{2})/(\d{2})/(\d{4})", tags.get("note", ""))
    if not m:
        return False
    try:
        d, mo, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
        return datetime(y, mo, d, tzinfo=timezone.utc).date() < today
    except ValueError:
        return False


def write_db(nodes, out_dir, keep_expired=False):
    """
    Converte os nos crus do OSM no radares.csv.gz.

    Separado do download de proposito: permite regerar a base a partir de um JSON
    ja baixado e, principalmente, deixa esta etapa testavel sem tocar na rede.
    Devolve (linhas, descartados_por_validade, sem_velocidade).
    """
    today = datetime.now(timezone.utc).date()
    rows, expired, no_speed = [], 0, 0
    seen_coords = set()

    for el in nodes.values():
        tags = el.get("tags", {})
        if not keep_expired and is_expired(tags, today):
            expired += 1
            continue
        lat, lon = round(el["lat"], 6), round(el["lon"], 6)
        # dedup posicional: dois nos a menos de ~1 m sao o mesmo radar
        key = (round(lat, 5), round(lon, 5))
        if key in seen_coords:
            continue
        seen_coords.add(key)
        speed = parse_speed(tags)
        if speed == 0:
            no_speed += 1
        # O rotulo e o ultimo campo, mas um ';' vindo do OSM ainda quebraria a
        # contagem de campos do parser Kotlin. Troca por virgula.
        label = parse_label(tags).replace(";", ",")
        rows.append(format(lat, ".6f") + ";" + format(lon, ".6f") + ";"
                    + str(speed) + ";" + str(parse_direction(tags)) + ";" + label)

    # A busca binaria do app depende desta ordenacao.
    rows.sort(key=lambda r: float(r.split(";", 1)[0]))

    os.makedirs(out_dir, exist_ok=True)
    # mtime=0 deixa o gzip reproduzivel (mesma entrada -> mesmo arquivo)
    with gzip.GzipFile(os.path.join(out_dir, "radares.csv.gz"),
                       "wb", compresslevel=9, mtime=0) as fh:
        fh.write(("\n".join(rows) + "\n").encode("utf-8"))

    return rows, expired, no_speed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--area", default="BR",
                    help="codigo ISO do pais (padrao BR); ignorado se --bbox for usado")
    ap.add_argument("--bbox", help="sul,oeste,norte,leste; forca o modo bbox")
    ap.add_argument("--step", type=float, default=5.0, help="tamanho do bloco em graus")
    ap.add_argument("--out", default=ASSETS, help="pasta de saida")
    ap.add_argument("--keep-expired", action="store_true",
                    help="manter radares com afericao Inmetro vencida")
    args = ap.parse_args()

    nodes = {}
    bbox = tuple(float(x) for x in args.bbox.split(",")) if args.bbox else BRASIL

    # Caminho preferido: uma requisicao so, filtrando pela fronteira do pais.
    if not args.bbox:
        log("Baixando radares de " + args.area + " numa unica requisicao...")
        try:
            for el in fetch_country(args.area):
                nodes[el["id"]] = el
            log("  -> " + str(len(nodes)) + " radares")
        except Exception as exc:
            log("  falhou (" + type(exc).__name__ + "); caindo pro modo bbox")

    # Plano B (ou --bbox explicito): varrer em blocos, subdividindo onde pesar.
    if not nodes:
        blocks = list(tiles(bbox, args.step))
        log("Baixando radares em " + str(len(blocks)) + " bloco(s)...")
        for i, (s, w, n, e) in enumerate(blocks, 1):
            log("  [" + str(i) + "/" + str(len(blocks)) + "] bbox("
                + format(s, ".1f") + "," + format(w, ".1f") + ","
                + format(n, ".1f") + "," + format(e, ".1f") + ")")
            found = fetch_area(s, w, n, e)
            for el in found:
                nodes[el["id"]] = el   # dedup por id do OSM
            log("      -> " + str(len(found)) + " radares (acumulado: "
                + str(len(nodes)) + ")")

    if not nodes:
        log("ERRO: nenhum radar baixado; base anterior mantida intacta.")
        return 1

    rows, expired, no_speed = write_db(nodes, args.out, args.keep_expired)
    csv_path = os.path.join(args.out, "radares.csv.gz")

    meta = {
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "OpenStreetMap (ODbL) via Overpass API",
        "method": ("bbox " + ",".join(format(v, ".2f") for v in bbox)
                   if args.bbox else "area ISO3166-1=" + args.area),
        "count": len(rows),
        "with_maxspeed": len(rows) - no_speed,
        "dropped_expired_inmetro": expired,
    }
    with open(os.path.join(args.out, "radares.meta.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, ensure_ascii=False, indent=2)

    pct = (len(rows) - no_speed) * 100 // max(len(rows), 1)
    log("")
    log("OK  " + str(len(rows)) + " radares -> " + csv_path
        + " (" + format(os.path.getsize(csv_path) / 1024, ".0f") + " KB comprimido)")
    log("    com velocidade: " + str(len(rows) - no_speed) + " (" + str(pct) + "%)")
    log("    descartados por afericao Inmetro vencida: " + str(expired))


if __name__ == "__main__":
    sys.exit(main() or 0)
