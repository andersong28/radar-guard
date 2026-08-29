#!/usr/bin/env python3
"""
simular_alertas.py - Confere a base e simula uma passagem por um radar.

Faz duas coisas:

1. VALIDA o radares.csv.gz contra o contrato exato que o parser Kotlin
   (RadarDatabase.parse) espera: 5 campos por linha separados por ';', numeros
   parseaveis, ordenacao por latitude, sem ';' extra vindo do rotulo.

2. SIMULA uma aproximacao em linha reta a uma velocidade dada e imprime a
   sequencia de alertas com o tempo de antecedencia de cada um. Serve para
   calibrar a escala antes de sair dirigindo: da pra ver na hora que, a 110 km/h,
   o aviso de 100 m chega 3 segundos antes do radar.

Uso:
    python tools/simular_alertas.py
    python tools/simular_alertas.py --velocidade 110 --radar 3
"""

import argparse
import gzip
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CSV = os.path.normpath(os.path.join(
    HERE, "..", "app", "src", "main", "assets", "radares.csv.gz"))

METERS_PER_DEG_LAT = 111_320.0


def meters_per_deg_lon(lat):
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat))


def distance(lat1, lon1, lat2, lon2):
    m = meters_per_deg_lon((lat1 + lat2) * 0.5)
    dy = (lat2 - lat1) * METERS_PER_DEG_LAT
    dx = (lon2 - lon1) * m
    return math.hypot(dx, dy)


def load(path):
    """Le e valida. Devolve (radares, problemas)."""
    rows, problems = [], []
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split(";")
            if len(parts) != 5:
                problems.append("linha " + str(lineno) + ": "
                                + str(len(parts)) + " campos (esperado 5) -> " + line[:70])
                continue
            try:
                lat, lon = float(parts[0]), float(parts[1])
                spd, dirn = int(parts[2]), int(parts[3])
            except ValueError:
                problems.append("linha " + str(lineno) + ": campo numerico invalido -> " + line[:70])
                continue
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                problems.append("linha " + str(lineno) + ": coordenada fora de faixa")
                continue
            if spd < 0 or spd > 140:
                problems.append("linha " + str(lineno) + ": velocidade absurda " + str(spd))
            if dirn != -1 and not (0 <= dirn <= 359):
                problems.append("linha " + str(lineno) + ": direcao invalida " + str(dirn))
            rows.append((lat, lon, spd, dirn, parts[4]))
    return rows, problems


def simulate(radar, speed_kmh, stages, voice_above, tolerance):
    """
    Aproximacao em linha reta vindo do sul, uma leitura de GPS por segundo.
    Reproduz a regra do AlertEngine: cada estagio dispara uma vez, e quando um
    ciclo cruza varios estagios de uma vez anuncia o mais apertado deles.
    """
    lat, lon, limit, _dirn, label = radar
    speed_ms = speed_kmh / 3.6
    start = 2600.0

    fired = set()
    events = []
    t = 0.0
    d = start
    while d > 0:
        crossed = [s for s in stages if d <= s and s not in fired]
        if crossed:
            fired.update(crossed)
            stage = min(crossed)
            mode = "voz  " if stage >= voice_above else "bipe "
            events.append((t, stage, d, mode))
        t += 1.0
        d -= speed_ms

    over = limit > 0 and speed_kmh > limit + tolerance
    return events, over, limit, label


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=CSV)
    ap.add_argument("--velocidade", type=float, default=110.0, help="km/h")
    ap.add_argument("--radar", type=int, default=0, help="indice do radar a simular")
    ap.add_argument("--estagios", default="2000,1000,500,300,200,100")
    ap.add_argument("--falar-acima", type=int, default=300)
    ap.add_argument("--tolerancia", type=int, default=3)
    args = ap.parse_args()

    if not os.path.exists(args.csv):
        print("Base nao encontrada: " + args.csv)
        print("Rode antes: python tools/build_radar_db.py")
        return 1

    rows, problems = load(args.csv)

    print("=" * 68)
    print("VALIDACAO DA BASE")
    print("=" * 68)
    print("arquivo   : " + args.csv)
    print("tamanho   : " + format(os.path.getsize(args.csv) / 1024, ".0f") + " KB comprimido")
    print("radares   : " + str(len(rows)))

    com_vel = sum(1 for r in rows if r[2] > 0)
    com_dir = sum(1 for r in rows if r[3] != -1)
    com_lbl = sum(1 for r in rows if r[4])
    if rows:
        print("com limite: " + str(com_vel) + " (" + str(com_vel * 100 // len(rows)) + "%)")
        print("com sentido: " + str(com_dir) + " (" + str(com_dir * 100 // len(rows)) + "%)")
        print("com rotulo : " + str(com_lbl) + " (" + str(com_lbl * 100 // len(rows)) + "%)")

    ordenado = all(rows[i][0] <= rows[i + 1][0] for i in range(len(rows) - 1))
    print("ordenado por latitude: " + ("sim" if ordenado else "NAO - a busca binaria do app quebra!"))

    if problems:
        print("\nPROBLEMAS (" + str(len(problems)) + "):")
        for p in problems[:15]:
            print("  " + p)
    else:
        print("formato   : OK, compativel com RadarDatabase.parse")

    if not rows:
        return 1

    # Um radar com limite conhecido faz a simulacao ficar mais ilustrativa.
    idx = args.radar
    if idx == 0:
        for i, r in enumerate(rows):
            if r[2] > 0 and r[4]:
                idx = i
                break
    idx = max(0, min(idx, len(rows) - 1))

    stages = sorted({int(x) for x in args.estagios.split(",") if x.strip()}, reverse=True)
    events, over, limit, label = simulate(
        rows[idx], args.velocidade, stages, args.falar_acima, args.tolerancia)

    print()
    print("=" * 68)
    print("SIMULACAO DE APROXIMACAO")
    print("=" * 68)
    print("radar     : " + (label if label else "(sem rotulo)")
          + "  " + format(rows[idx][0], ".5f") + ", " + format(rows[idx][1], ".5f"))
    print("limite    : " + (str(limit) + " km/h" if limit else "desconhecido"))
    print("sua velocidade: " + format(args.velocidade, ".0f") + " km/h  ("
          + format(args.velocidade / 3.6, ".1f") + " m/s)")
    if over:
        print("ATENCAO   : acima do limite -> o app repetiria o aviso de excesso a cada 4 s")
    print()
    print("  aviso        distancia   antecedencia")
    print("  " + "-" * 44)
    for t, stage, d, mode in events:
        falta = d / (args.velocidade / 3.6)
        rotulo = (format(stage / 1000, ".0f") + " km" if stage >= 1000
                  else str(stage) + " m")
        print("  " + mode + rotulo.rjust(6) + "   " + format(d, "7.0f") + " m"
              + "   " + format(falta, "5.1f") + " s antes")
    print()
    print("Total de avisos por radar: " + str(len(events))
          + "  (o Waze daria 1)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
