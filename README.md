# RadarGuard

Alerta de radares para Android com **avisos escalonados**: 2 km, 1 km, 500 m, 300 m,
200 m e 100 m — cada distância avisa **uma vez por radar**, em vez do aviso único do Waze.
Funciona **100% offline**.

Não é um navegador. Não calcula rota e não substitui o Waze ou o Google Maps —
ele roda **junto**, por cima ou em segundo plano, cuidando só dos radares.

---

## O que ele faz

- **Escala de avisos configurável.** As seis distâncias padrão são editáveis nos Ajustes.
- **Voz nos avisos distantes, bipe nos próximos.** Acima de 300 m ele fala
  ("Radar a 1 quilômetro, limite 80"); abaixo disso só bipa, com o tom subindo conforme
  aproxima — a 100 km/h não existe tempo para uma frase terminar antes do radar.
- **Só alerta radar no seu sentido.** Compara o rumo do seu deslocamento com o rumo até
  o radar. Não apita para radar da pista contrária.
- **Aviso de excesso de velocidade.** A menos de 400 m de um radar, se você estiver acima
  do limite, ele repete o aviso a cada 4 s. É este alerta que efetivamente evita a multa —
  saber que o radar existe não adianta se você não reduzir.
- **Abaixa a música em vez de cortar.** Usa a prioridade de áudio de navegação, então
  o som sai por cima do Waze e do rádio, inclusive no Bluetooth do carro.
- **Leve.** Zero bibliotecas externas: sem AndroidX, sem Google Play Services, sem Compose.
  Toda a base de radares fica na RAM em menos de 1 MB.

## O que ele não faz

- Não tem mapa nem rota.
- Não detecta **radar móvel / portátil** — nenhum aplicativo do mundo detecta, porque
  eles não têm posição fixa para ser mapeada.
- Não cobre 100% dos radares fixos. Nenhuma base cobre, inclusive a do Waze.

---

## Colocando no seu celular

Você não precisa instalar Android Studio: o GitHub compila o APK de graça na nuvem.

1. **Crie um repositório** no GitHub (pode ser privado) e suba esta pasta:

   ```bash
   cd radar-guard
   git init
   git add .
   git commit -m "RadarGuard inicial"
   git branch -M main
   git remote add origin https://github.com/SEU-USUARIO/radar-guard.git
   git push -u origin main
   ```

2. **Espere o build.** Na aba **Actions** do repositório, o fluxo *Gerar APK* roda
   sozinho a cada push. Leva uns 3–5 minutos.

3. **Baixe o APK.** Abra a execução concluída e baixe o artefato **RadarGuard-APK**.
   Transfira o arquivo para o celular.

4. **Instale.** O Android vai pedir para autorizar "instalar apps desconhecidos" —
   é esperado, já que o APK não veio da Play Store.

5. **Dê as permissões**: localização (*Permitir o tempo todo* ou *Ao usar o app*) e
   notificações. Sem elas o serviço não roda.

> O APK é assinado com a chave de debug do Android, o que é suficiente para uso
> pessoal. Para publicar na Play Store seria preciso gerar uma chave de upload própria.

---

## Mantendo a base de radares atualizada

O fluxo **Atualizar base de radares** roda automaticamente no dia 1 de cada mês,
regera a base do OpenStreetMap e publica num release de tag fixa.

Para o app se atualizar sozinho sem você recompilar nada, cole esta URL no campo
**URL da base atualizada**, nos Ajustes (troque `SEU-USUARIO/radar-guard`):

```
https://github.com/SEU-USUARIO/radar-guard/releases/download/base-radares/radares.csv.gz
```

Depois é só tocar em **Atualizar base agora** quando estiver no Wi-Fi. A base baixada
só substitui a anterior depois de ser lida com sucesso — download interrompido não
deixa o app sem radares.

Para gerar a base localmente:

```bash
python tools/build_radar_db.py                            # Brasil inteiro
python tools/build_radar_db.py --bbox -33.8,-57.7,-27.0,-49.6   # só o RS
```

O download do Brasil inteiro leva **menos de um minuto**, numa requisição só, usando
o filtro de fronteira do Overpass. Isso é contra-intuitivo mas importa: o Overpass
limita por *número* de requisições, não por volume — quebrar o país em dezenas de
blocos rende `HTTP 429` atrás de 429 e demora horas. O modo por blocos continua no
script como plano B (e é o que `--bbox` força), com espera no 429 e subdivisão da
área quando o servidor não aguenta.

Para conferir a base e ouvir como a escala se comporta numa velocidade específica:

```bash
python tools/simular_alertas.py --velocidade 110
```

---

## Ajustando os alertas

| Ajuste | Padrão | Para que serve |
|---|---|---|
| Distâncias de aviso | `2000,1000,500,300,200,100` | A escala completa. Edite à vontade. |
| Falar acima de | `300` m | Acima disso fala a frase; abaixo, só bipa. |
| Aviso de excesso | ligado | Repete o alerta se você estiver acima do limite perto do radar. |
| Tolerância do excesso | `3` km/h | O GPS lê um pouco abaixo do velocímetro do carro. |
| Velocidade mínima | `20` km/h | Abaixo disso fica calado (parado, a pé, trânsito). |
| Cone frontal | `30°` | Ângulo em que o radar conta como "à frente". |

**Sobre a escala:** a 110 km/h você percorre ~30 m por segundo. Então 2000 m dá
**65 segundos** de antecedência; 500 m dá 16 s; e 100 m dá **3 segundos** — por isso
os estágios curtos são bipe, não fala. Use o botão **Ouvir a escala completa** para
calibrar antes de sair dirigindo.

**Sobre o cone:** um valor menor reduz alerta da pista contrária, mas pode perder um
radar logo depois de uma curva. 30° é um bom meio-termo.

O cone faz quase todo o trabalho porque o sentido declarado do radar quase nunca é
aproveitável: cerca de 21% dos radares trazem a tag `direction`, mas metade deles usa
`forward`/`backward`, que é relativo à geometria da via no OpenStreetMap e não vira um
rumo em graus sem baixar a via inteira. Sobram ~10% com rumo numérico usável — esses
ganham um filtro extra de sentido, e o resto depende do cone.

---

## Fonte dos dados

Os radares vêm do **OpenStreetMap** (nós `highway=speed_camera`), sob licença **ODbL**.
A base atual tem **9.018 radares** no Brasil, dos quais **89% trazem o limite de
velocidade** — por isso o app consegue anunciar "limite 80" e avisar quando você está
acima dele. O arquivo fica em **82 KB** comprimido.

Vale saber por que não é do INMETRO diretamente: o INMETRO é quem afere por lei todo
medidor de velocidade do país e publica o registro no
[portal PSIE](https://servicos.rbmlq.gov.br/Instrumento) — mas **sem coordenadas**,
só com endereço em texto ("CE-050, km 6,0"), o que não serve para alerta por
proximidade. A comunidade OpenStreetMap do Brasil já cruza esse registro e adiciona
as coordenadas, então o OSM acaba sendo a melhor fonte utilizável. Onde o radar tem
`note=Validade: dd/mm/aaaa` do INMETRO e a aferição está vencida, o gerador descarta
o ponto, por ser provável equipamento desativado.

Encontrou um radar faltando? Ele pode ser adicionado no próprio OpenStreetMap e entra
na base do mês seguinte.

---

## Estrutura

```
tools/build_radar_db.py    gera radares.csv.gz do OpenStreetMap
tools/simular_alertas.py   valida a base e simula uma passagem por um radar
app/src/main/java/com/radarguard/
  GeoMath.kt               distância e rumo em plano tangente
  RadarDatabase.kt         base inteira em arrays primitivos na RAM
  AlertEngine.kt           máquina de estados dos alertas (o núcleo)
  AlertPlayer.kt           voz (TTS) + bipes sintetizados + foco de áudio
  LocationService.kt       serviço em primeiro plano lendo o GPS
  MainActivity.kt          tela de direção
  SettingsActivity.kt      ajustes
.github/workflows/         build do APK e atualização mensal da base
```

---

## Aviso

Alertar sobre radares fixos é legal no Brasil — é o que o Waze e o Google Maps fazem.
Ainda assim, este aplicativo existe para você **dirigir dentro do limite com mais
consciência**, não para burlar fiscalização. O limite de velocidade continua valendo
onde não há radar, e é lá que a maioria dos acidentes acontece.
