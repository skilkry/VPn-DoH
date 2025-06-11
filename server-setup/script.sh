#!/bin/bash
#
# Script para crear todos los archivos necesarios para la instalación de DoT.
# Ejecútalo una vez para generar todos los demás scripts en el directorio actual.
#Daniel Enrique Cayuelas as D.E.C whole doc
# ----------------------------------------------------------------
# Script:       Setup_DNS_Server.sh
# Author:       skilkry (Daniel Sardina Sedano) & D.E.C
# Date:         2025-06-11
# Copyright (c) 2025 skilkry & D.E.C . All rights reserved.
# Licensed under the MIT License.
#
# Description:  Este script automatiza la instalación y configuración
#               de un servidor DNS con BIND9 y Nginx como proxy inverso.
# ----------------------------------------------------------------



echo "⚙️  Creando los scripts de instalación de DNS-over-TLS..."
echo "----------------------------------------------------"

# ==============================================================================
#                                   1. common.sh
# ==============================================================================
echo "    -> Creando common.sh..."
cat <<'EOF' > common.sh
#!/bin/bash
# common.sh: Funciones y variables comunes

# Colores y Símbolos
NC="\033[0m"; RED="\033[0;31m"; GREEN="\033[0;32m"; YELLOW="\033[0;33m"; BLUE="\033[0;34m"; PURPLE="\033[0;35m"; CYAN="\033[0;36m"; ORANGE="\033[0;91m"; LIGHT_BLUE="\033[1;34m"
CHECK_MARK="${GREEN}✓${NC}"; CROSS_MARK="${RED}✗${NC}"; INFO_ICON="${BLUE}i${NC}"; WARNING_ICON="${YELLOW}!${NC}"; ERROR_ICON="${RED}✗${NC}"; BOLD="\033[1m"
PROMPT_PREFIX="${CYAN}ventaone@terminal ${GREEN}$ ${NC}"

# Funciones de Impresión
function print_info() { echo -e "${INFO_ICON} ${CYAN}$1${NC}"; }
function print_success() { echo -e "${CHECK_MARK} ${GREEN}$1${NC}"; }
function print_warning() { echo -e "${WARNING_ICON} ${YELLOW}$1${NC}"; }
function print_error_and_exit() { echo -e "${ERROR_ICON} ${RED}$1${NC}" >&2; exit 1; }

# Spinner
function run_with_spinner() {
    local pid; local delay=0.1; local spin_chars="⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"; local i=0; local message="$1"; local command="$2"
    echo -n " ${INFO_ICON} ${LIGHT_BLUE}${message}...${NC} "
    eval "$command" &>/dev/null &
    pid=$!; sleep 0.2
    while ps -p $pid &>/dev/null; do i=$(( (i+1) % ${#spin_chars} )); echo -en "${LIGHT_BLUE}${spin_chars:$i:1}${NC}\033[K\r"; sleep "$delay"; done
    echo -ne "\r\033[K"; wait $pid; local exit_code=$?
    if [ $exit_code -eq 0 ]; then echo -e "${CHECK_MARK} ${GREEN}${message} ${BOLD}Completado.${NC}"; return 0;
    else echo -e "${CROSS_MARK} ${RED}${message} ${BOLD}Falló.${NC}"; return 1; fi
}

# Gestión de Limpieza
function register_cleanup_task() { echo "$@" >> "/tmp/dot_cleanup_tasks"; }
function run_cleanup_tasks() {
    if [ -f "/tmp/dot_cleanup_tasks" ]; then
        print_warning "Ejecutando tareas de limpieza..."; bash "/tmp/dot_cleanup_tasks" &>/dev/null
        rm -f "/tmp/dot_cleanup_tasks"; print_info "Tareas de limpieza completadas.";
    fi
}

# Verificación Sudo
function check_sudo_privileges() {
    print_info "Verificando privilegios de sudo...";
    [[ "$EUID" -ne 0 ]] && print_error_and_exit "Ejecutar con sudo."
    print_success "Privilegios sudo OK.";
}

# Gestión de Servicios
function restart_service() {
    source config.sh; local service_name="$1"
    print_info "Reiniciando: ${BOLD}$service_name${NC}."
    if command -v systemctl &>/dev/null; then
        if systemctl restart "$service_name" &>/dev/null; then print_success "$service_name reiniciado."; return 0;
        else
            print_warning "Fallo al reiniciar. Intentando iniciar...";
            if systemctl start "$service_name" &>/dev/null; then print_success "$service_name iniciado."; return 0;
            else print_error_and_exit "Fallo al iniciar/reiniciar $service_name."; fi
        fi
    else print_error_and_exit "No se encontró systemctl."; fi
    return 1;
}

# Mensaje Post-Instalación Paquete
function display_package_version_and_prompt() {
    local package_name="$1"; local version_command="$2"; local service_name="$3"
    echo -e "${BLUE}──────────────────────────────────────────────────────────────────────────${NC}"
    print_info "Comprobando ${BOLD}$package_name${NC}..."
    echo -e "${LIGHT_BLUE}Versión:${NC}"; local version_output
    if ! version_output=$($version_command 2>&1); then print_warning "No se pudo obtener la versión."; echo -e "${RED}Error: ${NC}$version_output";
    else echo -e "${GREEN}$version_output${NC}"; fi
    [ -n "$service_name" ] && command -v systemctl &>/dev/null && print_info "Estado:" && systemctl status "$service_name" --no-pager
    echo -e "${BLUE}──────────────────────────────────────────────────────────────────────────${NC}"
    while true; do
        read -rp "${PROMPT_PREFIX} ¿Continuar? (y/n): " confirm;
        if [[ "$confirm" =~ ^[Yy]$ ]]; then print_info "Continuando..."; break;
        elif [[ "$confirm" =~ ^[Nn]$ ]]; then print_error_and_exit "Cancelado.";
        else print_warning "Inválido."; fi
    done; echo ""
}

# Gestión de Paquetes
function install_package() {
    source config.sh; local package_name="$1"
    print_info "Instalando: ${BOLD}$package_name${NC}."
    case "$PACKAGE_MANAGER" in
        apt) cmd="DEBIAN_FRONTEND=noninteractive apt install -y $package_name" ;;
        dnf|yum) cmd="$PACKAGE_MANAGER install -y $package_name" ;;
        *) print_error_and_exit "Gestor de paquetes no soportado.";;
    esac
    run_with_spinner "Instalando $package_name" "$cmd" || print_error_and_exit "Error al instalar $package_name."
    print_success "$package_name instalado."
}

function update_packages() {
    source config.sh; print_info "Actualizando paquetes."
    case "$PACKAGE_MANAGER" in
        apt) run_with_spinner "apt update" "apt update -y" || print_error_and_exit "Fallo apt update.";
             run_with_spinner "apt upgrade" "apt upgrade -y" || print_error_and_exit "Fallo apt upgrade."; ;;
        dnf|yum) run_with_spinner "$PACKAGE_MANAGER update" "$PACKAGE_MANAGER update -y" || print_error_and_exit "Fallo $PACKAGE_MANAGER update."; ;;
    esac
    print_success "Paquetes actualizados."
}

# Inicializar archivo de limpieza
> "/tmp/dot_cleanup_tasks"
EOF

# ==============================================================================
#                               2. config.template.sh
# ==============================================================================
echo "    -> Creando config.template.sh..."
cat <<'EOF' > config.template.sh
#!/bin/bash
# config.sh: Configuración Global

# User Input
DOMAIN=""
EMAIL=""
USE_IPV6="no"

# System Detection
DISTRO=""
PACKAGE_MANAGER=""
SERVICE_MANAGER=""
FIREWALL_MANAGER=""
SERVER_IP=""

# Constants
STUBBY_USER="stubby-dot"
STUBBY_GROUP="stubby-dot"
DOH_PORT=853
STUBBY_LISTEN_PORT=5300
NGINX_MAIN_CONF_FILE="/etc/nginx/nginx.conf"
NGINX_HTTP_CONF_PATH="/etc/nginx/sites-available/dot-http.conf"
NGINX_HTTP_SYMLINK_PATH="/etc/nginx/sites-enabled/dot-http.conf"
NGINX_STREAM_DIR="/etc/nginx/stream.d"
NGINX_STREAM_CONF_PATH="/etc/nginx/stream.d/dot_stream.conf"
STUBBY_CONF_PATH="/etc/stubby/stubby.yml"
DHPARAM_PATH="/etc/ssl/certs/dhparam.pem"
CLEANUP_FILE="/tmp/dot_cleanup_tasks"

# Ensure cleanup file is empty at start
> "$CLEANUP_FILE"
EOF

# ==============================================================================
#                               3. detect_system.sh
# ==============================================================================
echo "    -> Creando detect_system.sh..."
cat <<'EOF' > detect_system.sh
#!/bin/bash
# detect_system.sh: Detecta SO, paquetes, firewall, IP.
source common.sh; source config.sh

print_info "Detectando sistema..."
if grep -Eq "debian|ubuntu" /etc/os-release; then DISTRO="Debian/Ubuntu"; PACKAGE_MANAGER="apt";
elif grep -Eq "centos|redhat|fedora" /etc/os-release; then DISTRO="CentOS/RHEL/Fedora"; PACKAGE_MANAGER="dnf";
    ! command -v dnf &>/dev/null && PACKAGE_MANAGER="yum";
else print_error_and_exit "Distribución no compatible."; fi
print_success "Distro: ${BOLD}${DISTRO}${NC}, Gestor: ${BOLD}${PACKAGE_MANAGER}${NC}."
sed -i "s/^DISTRO=.*/DISTRO=\"$DISTRO\"/" config.sh
sed -i "s/^PACKAGE_MANAGER=.*/PACKAGE_MANAGER=\"$PACKAGE_MANAGER\"/" config.sh

if command -v systemctl &>/dev/null; then SERVICE_MANAGER="systemd"; print_success "Servicios: systemd.";
else print_warning "No systemd detectado."; SERVICE_MANAGER="init"; fi
sed -i "s/^SERVICE_MANAGER=.*/SERVICE_MANAGER=\"$SERVICE_MANAGER\"/" config.sh

if command -v ufw &>/dev/null; then FIREWALL_MANAGER="ufw"; print_success "Firewall: UFW.";
elif command -v firewall-cmd &>/dev/null; then FIREWALL_MANAGER="firewalld"; print_success "Firewall: FirewallD.";
elif command -v iptables &>/dev/null; then FIREWALL_MANAGER="iptables"; print_success "Firewall: iptables.";
else print_warning "No firewall detectado."; FIREWALL_MANAGER=""; fi
sed -i "s/^FIREWALL_MANAGER=.*/FIREWALL_MANAGER=\"$FIREWALL_MANAGER\"/" config.sh

SERVER_IP=$(dig +short myip.opendns.com @resolver1.opendns.com || curl -s ifconfig.me)
[ -z "$SERVER_IP" ] && print_warning "No se pudo obtener IP pública." || print_success "IP pública: ${BOLD}${SERVER_IP}${NC}"
sed -i "s/^SERVER_IP=.*/SERVER_IP=\"$SERVER_IP\"/" config.sh

print_success "Detección completada."
EOF

# ==============================================================================
#                               4. check_dns.sh
# ==============================================================================
echo "    -> Creando check_dns.sh..."
cat <<'EOF' > check_dns.sh
#!/bin/bash
# check_dns.sh: Verifica la propagación DNS.
source common.sh; source config.sh

print_info "Iniciando comprobación DNS para ${BOLD}$DOMAIN${NC}."
retries=0; max_retries=10; delay=15
if [ -z "$SERVER_IP" ]; then print_warning "Sin IP, saltando verificación."; read -rp "${PROMPT_PREFIX} Enter para continuar..."; exit 0; fi

print_info "Verificando A (IPv4) -> ${BOLD}$SERVER_IP${NC}."
while [ $retries -lt $max_retries ]; do
    ip=$(dig +short A "$DOMAIN" @8.8.8.8 | head -n 1)
    if [ "$ip" == "$SERVER_IP" ]; then print_success "Registro A OK."; break; fi
    print_warning "A no propagado (Actual: ${ip:-N/A}). Reintentando... (${retries}/${max_retries})"; sleep "$delay"; retries=$((retries + 1))
    [ $retries -eq $max_retries ] && print_error_and_exit "Registro A no propagado."
done

if [ "$USE_IPV6" == "yes" ]; then
    ipv6=$(ip -6 addr show scope global | grep -oP 'inet6\s+\K[0-9a-f:]+' | head -n 1)
    if [ -z "$ipv6" ]; then print_warning "Sin IPv6, saltando AAAA."; exit 0; fi
    print_info "Verificando AAAA (IPv6) -> ${BOLD}$ipv6${NC}."
    retries=0
    while [ $retries -lt $max_retries ]; do
        ip6=$(dig +short AAAA "$DOMAIN" @2606:4700:4700::1111 | head -n 1)
        if [[ "$ip6" == *"$ipv6"* ]]; then print_success "Registro AAAA OK."; break; fi
        print_warning "AAAA no propagado (Actual: ${ip6:-N/A}). Reintentando..."; sleep "$delay"; retries=$((retries + 1))
        [ $retries -eq $max_retries ] && print_warning "AAAA no propagado. ¡Continuando de todos modos!"
    done
fi
print_success "Verificación DNS completada."
EOF

# ==============================================================================
#                               5. setup_nginx.sh
# ==============================================================================
echo "    -> Creando setup_nginx.sh..."
cat <<'EOF' > setup_nginx.sh
#!/bin/bash
# setup_nginx.sh: Instala Nginx.
source common.sh; source config.sh

print_info "Instalando Nginx."
if command -v nginx &>/dev/null; then
    read -rp "${PROMPT_PREFIX} Nginx detectado. ¿Purgar? (y/n): " purge
    if [[ "$purge" =~ ^[Yy]$ ]]; then
        run_with_spinner "Parando Nginx" "systemctl stop nginx || true"
        run_with_spinner "Purgando Nginx" "$PACKAGE_MANAGER remove --purge -y nginx nginx-common nginx-full || true"
        [ "$PACKAGE_MANAGER" == "apt" ] && run_with_spinner "Limpiando" "apt autoremove -y || true"
        run_with_spinner "Eliminando dirs" "rm -rf /etc/nginx /var/lib/nginx /var/cache/nginx /var/log/nginx || true"
        print_success "Purga OK."
    else print_error_and_exit "Purga cancelada."; fi
else print_info "No Nginx previo."; fi

update_packages
install_package "nginx"
print_success "Nginx instalado."
display_package_version_and_prompt "Nginx" "nginx -v" "nginx"
EOF

# ==============================================================================
#                               6. setup_certbot.sh
# ==============================================================================
echo "    -> Creando setup_certbot.sh..."
cat <<'EOF' > setup_certbot.sh
#!/bin/bash
# setup_certbot.sh: Instala Certbot y obtiene certificado.
source common.sh; source config.sh

function install_certbot() {
    print_info "Instalando Certbot."
    install_package "certbot" || print_error_and_exit "Fallo al instalar certbot."
    install_package "python3-certbot-nginx" || print_error_and_exit "Fallo al instalar plugin Nginx."
    print_success "Certbot instalado."
    display_package_version_and_prompt "Certbot" "certbot --version"
}

function configure_nginx_http_for_certbot() {
    print_info "Configurando Nginx HTTP para Certbot."
    user="www-data"; [ "$PACKAGE_MANAGER" != "apt" ] && user="nginx"
    run_with_spinner "Creando /var/www/html" "mkdir -p /var/www/html && chown $user:$user /var/www/html && chmod 755 /var/www/html" || print_error_and_exit "Fallo /var/www/html."
    register_cleanup_task "rm -rf /var/www/html"

    cat <<E_O_F > "$NGINX_HTTP_CONF_PATH"
server {
    listen 80; $( [ "$USE_IPV6" == "yes" ] && echo "listen [::]:80;" )
    server_name $DOMAIN; root /var/www/html;
    location /.well-known/acme-challenge/ { allow all; }
}
E_O_F
    [ -L "$NGINX_HTTP_SYMLINK_PATH" ] && rm "$NGINX_HTTP_SYMLINK_PATH"
    run_with_spinner "Creando enlace simbólico" "ln -s $NGINX_HTTP_CONF_PATH $NGINX_HTTP_SYMLINK_PATH" || print_error_and_exit "Fallo enlace."
    register_cleanup_task "rm -f $NGINX_HTTP_SYMLINK_PATH $NGINX_HTTP_CONF_PATH"

   nginx -t || print_error_and_exit "Config Nginx final errónea."

    restart_service "nginx"
    print_success "Nginx HTTP listo."
}

function get_cert() {
    print_info "Obteniendo certificado para ${BOLD}$DOMAIN${NC}."
    local cmd="certbot --nginx $DOMAIN --agree-tos --email $EMAIL --non-interactive --rsa-key-size 4096 --hsts --staple-ocsp"

    if [ -d "/etc/letsencrypt/live/$DOMAIN" ]; then
        print_warning "Certificado existente. Saltando obtención."
    else
        run_with_spinner "Obteniendo certificado" "$cmd" || print_error_and_exit "Fallo al obtener certificado. Revisa logs."
        print_success "Certificado obtenido."
    fi
    [ "$SERVICE_MANAGER" == "systemd" ] && run_with_spinner "Habilitando certbot.timer" "systemctl enable --now certbot.timer"
}

install_certbot
configure_nginx_http_for_certbot
get_cert
EOF

# ==============================================================================
#                               7. setup_stubby.sh
# ==============================================================================
echo "    -> Creando setup_stubby.sh..."
cat <<'EOF' > setup_stubby.sh
#!/bin/bash
# setup_stubby.sh: Instala y configura Stubby.
source common.sh; source config.sh

function install_stubby() {
    print_info "Instalando Stubby."
    deps="libyaml-dev libssl-dev libevent-dev cmake make gcc g++ libgetdns-dev"
    [ "$PACKAGE_MANAGER" != "apt" ] && deps="yaml-devel openssl-devel libevent-devel cmake make gcc gcc-c++ getdns-devel"
    install_package "$deps" || print_error_and_exit "Fallo dependencias Stubby."

    ver="0.4.2"; url="https://github.com/getdnsapi/stubby/archive/v${ver}.tar.gz"; tar="/tmp/stubby-${ver}.tar.gz"; dir="/tmp/stubby-${ver}"
    run_with_spinner "Descargando Stubby $ver" "wget -q $url -O $tar" || print_error_and_exit "Fallo descarga Stubby."
    register_cleanup_task "rm -f $tar"; run_with_spinner "Extrayendo Stubby" "tar -xzf $tar -C /tmp" || print_error_and_exit "Fallo extracción Stubby."
    register_cleanup_task "rm -rf $dir"; run_with_spinner "Compilando Stubby" "cd $dir && cmake . && make" || print_error_and_exit "Fallo compilación Stubby."
    run_with_spinner "Instalando Stubby" "cd $dir && make install" || print_error_and_exit "Fallo instalación Stubby."
    run_with_spinner "Creando /etc/stubby" "mkdir -p /etc/stubby" || print_error_and_exit "Fallo /etc/stubby."

    ! id -g "$STUBBY_GROUP" &>/dev/null && run_with_spinner "Creando grupo $STUBBY_GROUP" "groupadd --system $STUBBY_GROUP" && register_cleanup_task "groupdel $STUBBY_GROUP || true"
    ! id -u "$STUBBY_USER" &>/dev/null && run_with_spinner "Creando user $STUBBY_USER" "useradd --system --no-create-home --shell /sbin/nologin -g $STUBBY_GROUP $STUBBY_USER" && register_cleanup_task "userdel $STUBBY_USER || true"
    print_success "Stubby instalado."
    display_package_version_and_prompt "Stubby" "stubby -V" "stubby"
}

function configure_stubby() {
    print_info "Configurando Stubby."
    [ -f "$STUBBY_CONF_PATH" ] && mv "$STUBBY_CONF_PATH" "${STUBBY_CONF_PATH}.bak" && register_cleanup_task "mv ${STUBBY_CONF_PATH}.bak $STUBBY_CONF_PATH || true"
    cat <<E_O_F > "$STUBBY_CONF_PATH"
resolution_type: GETDNS_RESOLUTION_STUB
dns_transport_list: [ GETDNS_TRANSPORT_TLS ]
tls_authentication: GETDNS_AUTHENTICATION_REQUIRED
tls_query_padding_blocksize: 256
edns_client_subnet_private: 1
listen_addresses: [ 127.0.0.1@$STUBBY_LISTEN_PORT, 0::1@$STUBBY_LISTEN_PORT ]
round_robin_upstreams: 1; idle_timeout: 10000
upstream_recursive_servers:
  - address_data: 1.1.1.1
    tls_auth_name: "cloudflare-dns.com"
  - address_data: 1.0.0.1
    tls_auth_name: "cloudflare-dns.com"
app_settings: { log_level: 3, log_filename: /var/log/stubby.log }
E_O_F
    run_with_spinner "Permisos Stubby" "chown $STUBBY_USER:$STUBBY_GROUP $STUBBY_CONF_PATH && chmod 640 $STUBBY_CONF_PATH" || print_error_and_exit "Fallo permisos Stubby."
    run_with_spinner "Log Stubby" "touch /var/log/stubby.log && chown $STUBBY_USER:$STUBBY_GROUP /var/log/stubby.log" || print_warning "Fallo log Stubby."

    cat <<E_O_F > /etc/systemd/system/stubby.service
[Unit]
Description=Stubby DoT resolver; After=network.target
[Service]
ExecStart=/usr/local/bin/stubby -C $STUBBY_CONF_PATH
User=$STUBBY_USER; Group=$STUBBY_GROUP; Restart=on-failure; RestartSec=5
ReadWritePaths=/var/log/stubby.log
[Install]
WantedBy=multi-user.target
E_O_F
    run_with_spinner "Recargando systemd" "systemctl daemon-reload" || print_error_and_exit "Fallo systemd reload."
    run_with_spinner "Habilitando Stubby" "systemctl enable stubby" || print_error_and_exit "Fallo enable Stubby."
    register_cleanup_task "systemctl disable stubby || true; rm -f /etc/systemd/system/stubby.service"
    restart_service "stubby"
    print_success "Stubby configurado."
}

install_stubby
configure_stubby
EOF

# ==============================================================================
#                               8. setup_nginx_dot.sh
# ==============================================================================
echo "    -> Creando setup_nginx_dot.sh..."
cat <<'EOF' > setup_nginx_dot.sh
#!/bin/bash
# setup_nginx_dot.sh: Configura Nginx para DoT (Stream Proxy).
source common.sh; source config.sh

print_info "Configurando Nginx para DoT."
if [ ! -f "$DHPARAM_PATH" ]; then
    run_with_spinner "Generando dhparam.pem" "openssl dhparam -out $DHPARAM_PATH 2048" || print_error_and_exit "Fallo dhparam."
    register_cleanup_task "rm -f $DHPARAM_PATH"
else print_info "dhparam.pem ya existe."; fi

run_with_spinner "Creando $NGINX_STREAM_DIR" "mkdir -p $NGINX_STREAM_DIR" || print_error_and_exit "Fallo $NGINX_STREAM_DIR."
register_cleanup_task "rm -rf $NGINX_STREAM_DIR"

print_info "Creando $NGINX_STREAM_CONF_PATH."
cat <<E_O_F > "$NGINX_STREAM_CONF_PATH"
server {
    listen $DOH_PORT ssl;
    $( [ "$USE_IPV6" == "yes" ] && echo "    listen [::]:$DOH_PORT ssl;" )
    proxy_pass 127.0.0.1:$STUBBY_LISTEN_PORT;
    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256';
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL_STREAM:10m;
    ssl_session_timeout 1d;
    ssl_dhparam $DHPARAM_PATH;
    proxy_timeout 10s;
}
E_O_F
register_cleanup_task "rm -f $NGINX_STREAM_CONF_PATH"

print_info "Asegurando bloque 'stream' en $NGINX_MAIN_CONF_FILE."
if ! grep -q "stream {" "$NGINX_MAIN_CONF_FILE" || ! grep -q "include ${NGINX_STREAM_DIR}/\*.conf;" "$NGINX_MAIN_CONF_FILE"; then
    cp "$NGINX_MAIN_CONF_FILE" "${NGINX_MAIN_CONF_FILE}.bak-stream"
    register_cleanup_task "mv ${NGINX_MAIN_CONF_FILE}.bak-stream $NGINX_MAIN_CONF_FILE || true"
    sed -i '/^stream {/,/}$/d' "$NGINX_MAIN_CONF_FILE" # Remove old
    { echo ""; echo "stream {"; echo "    include ${NGINX_STREAM_DIR}/*.conf;"; echo "}"; } >> "$NGINX_MAIN_CONF_FILE"
    print_info "Bloque 'stream' añadido/actualizado."
else print_info "Bloque 'stream' ya configurado."; fi

print_info "Creando config Nginx HTTP/S final."
rm -f "$NGINX_HTTP_CONF_PATH" "$NGINX_HTTP_SYMLINK_PATH" # Remove Certbot temp one
FINAL_CONF="/etc/nginx/sites-available/default-dot"
FINAL_LINK="/etc/nginx/sites-enabled/default-dot"

# Evaluar si se incluye la opción extra de SSL de Certbot
if [ -f /etc/letsencrypt/options-ssl-nginx.conf ]; then
    SSL_EXTRA='    include /etc/letsencrypt/options-ssl-nginx.conf;'
else
    SSL_EXTRA=""
fi

cat <<E_O_F > "$FINAL_CONF"
server {
    listen 80;
    $( [ "$USE_IPV6" == "yes" ] && echo "    listen [::]:80;" )
    server_name $DOMAIN;
    return 301 https://\$host\$request_uri;
}

server {
    listen 443 ssl http2;
    $( [ "$USE_IPV6" == "yes" ] && echo "    listen [::]:443 ssl http2;" )
    server_name $DOMAIN;

    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    ssl_dhparam $DHPARAM_PATH;
    $SSL_EXTRA

    location / {
        return 200 "DoT Server Active ($DOMAIN:$DOH_PORT)";
        add_header Content-Type text/plain;
    }
}
E_O_F

[ -L "$FINAL_LINK" ] && rm "$FINAL_LINK"
ln -s "$FINAL_CONF" "$FINAL_LINK"
register_cleanup_task "rm -f $FINAL_CONF $FINAL_LINK"

run_with_spinner "Probando config final Nginx" "nginx -t" || print_error_and_exit "Config Nginx final errónea."
restart_service "nginx"
print_success "Nginx configurado como proxy DoT."


# ==============================================================================
#                               9. setup_firewall.sh
# ==============================================================================
echo "    -> Creando setup_firewall.sh..."
cat <<'EOF' > setup_firewall.sh
#!/bin/bash
# setup_firewall.sh: Configura el firewall.
source common.sh; source config.sh

print_info "Configurando firewall ($FIREWALL_MANAGER)."
case "$FIREWALL_MANAGER" in
    "ufw")
        ! ufw status | grep -q "Status: active" && run_with_spinner "Habilitando UFW" "ufw --force enable" && register_cleanup_task "ufw disable || true"
        run_with_spinner "Permitiendo SSH,HTTP,HTTPS,DoT" "ufw allow 22/tcp; ufw allow 80/tcp; ufw allow 443/tcp; ufw allow $DOH_PORT/tcp" || print_error_and_exit "Fallo UFW."
        ;;
    "firewalld")
        ! systemctl is-active firewalld &>/dev/null && run_with_spinner "Iniciando FirewallD" "systemctl start firewalld && systemctl enable firewalld" && register_cleanup_task "systemctl stop firewalld || true"
        run_with_spinner "Abriendo puertos FirewallD" "firewall-cmd --add-port=22/tcp --permanent; firewall-cmd --add-port=80/tcp --permanent; firewall-cmd --add-port=443/tcp --permanent; firewall-cmd --add-port=$DOH_PORT/tcp --permanent; firewall-cmd --reload" || print_error_and_exit "Fallo FirewallD."
        ;;
    "iptables")
        print_warning "Configurando iptables (persist. manual)."
        run_with_spinner "Abriendo puertos iptables" "iptables -A INPUT -p tcp -m multiport --dports 22,80,443,$DOH_PORT -j ACCEPT" || print_error_and_exit "Fallo iptables."
        ;;
    *) print_warning "No firewall, abrir puertos manual."; exit 0 ;;
esac
print_success "Firewall configurado."
EOF

# ==============================================================================
#                               10. setup_extras.sh
# ==============================================================================
echo "    -> Creando setup_extras.sh..."
cat <<'EOF' > setup_extras.sh
#!/bin/bash
# setup_extras.sh: Instala SNMP y Fail2ban.
source common.sh; source config.sh

function install_snmp() {
    print_info "Instalando SNMP."
    install_package "snmp snmpd snmp-mibs-downloader" || { print_warning "Fallo SNMP."; return 1; }
    [ -f "/etc/snmp/snmpd.conf" ] && mv /etc/snmp/snmpd.conf /etc/snmp/snmpd.conf.bak && register_cleanup_task "mv /etc/snmp/snmpd.conf.bak /etc/snmp/snmpd.conf || true"
    cat <<E_O_F > /etc/snmp/snmpd.conf
agentaddress  147.135.209.34,[::1]
rocommunity public default -V systemonly

rocommunity  Ventaone 51.89.33.125 -V systemonly
view systemonly included .1.3.6.1.2.1.1
view systemonly included .1.3.6.1.2.1.25.1
rouser authPrivUser authpriv -V systemonly

# include a all *.conf files in a directory
includeDir /etc/snmp/snmpd.conf.d

E_O_F
    run_with_spinner "Permisos snmpd.conf" "chmod 600 /etc/snmp/snmpd.conf"
    run_with_spinner "Descargando MIBs" "download-mibs" || print_warning "Fallo MIBs."
    run_with_spinner "Habilitando snmpd" "systemctl enable snmpd"
    restart_service "snmpd"
    [ "$FIREWALL_MANAGER" == "ufw" ] && run_with_spinner "Permitiendo SNMP" "ufw allow 161/udp"
    print_success "SNMP instalado."
    display_package_version_and_prompt "SNMP (snmpd)" "snmpd -v" "snmpd"
}

function install_fail2ban() {
    print_info "Instalando Fail2ban."
    install_package "fail2ban" || { print_warning "Fallo Fail2ban."; return 1; }
    [ -f "/etc/fail2ban/jail.local" ] && mv /etc/fail2ban/jail.local /etc/fail2ban/jail.local.bak && register_cleanup_task "mv /etc/fail2ban/jail.local.bak /etc/fail2ban/jail.local || true"
    cat <<E_O_F > /etc/fail2ban/jail.local
[sshd]
enabled = true; port = ssh; maxretry = 3
E_O_F
    run_with_spinner "Habilitando fail2ban" "systemctl enable fail2ban"
    restart_service "fail2ban"
    print_success "Fail2ban instalado."
    display_package_version_and_prompt "Fail2ban" "fail2ban-client --version" "fail2ban"
}

install_snmp
install_fail2ban
print_success "Extras instalados."
EOF

# ==============================================================================
#                               11. main.sh
# ==============================================================================
#!/bin/bash
# main.sh: Orquestador Principal de Instalación DoT

# Verificar y preparar entorno
[ ! -f "common.sh" ] && echo "ERROR: common.sh no encontrado." && exit 1
[ ! -f "config.template.sh" ] && echo "ERROR: config.template.sh no encontrado." && exit 1
cp config.template.sh config.sh || { echo "ERROR: No se pudo copiar config.sh."; exit 1; }

INSTALL_COMPLETE=false

# Función de limpieza final
function cleanup_on_exit() {
    source common.sh
    if [ "$INSTALL_COMPLETE" = false ]; then
        print_warning "\n¡Instalación cancelada o fallida!"
        run_cleanup_tasks
        print_info "Limpieza completada."
    else
        print_success "\n¡Instalación completada con éxito!"
    fi
    rm -f config.sh
    exit 0
}
trap cleanup_on_exit INT TERM EXIT

# Mensaje inicial
function print_welcome_message() {
    clear
    echo -e "${BLUE}──────────────────────────────────────────────────────────────────────────${NC}"
    echo -e "${BLUE}  Asistente de Configuración DNS over TLS (DoT) v1.5 (Refactorizado)       ${NC}"
    echo -e "${BLUE}──────────────────────────────────────────────────────────────────────────${NC}"
    echo -e "  ${WARNING_ICON} Necesitas un ${BOLD}dominio${NC} y puertos ${BOLD}80, 443, 853 TCP${NC} libres."
    read -rp "${PROMPT_PREFIX} Presiona Enter para continuar..."
    echo ""
}

# Pedir datos al usuario y guardar en config.sh
function get_user_input() {
    source common.sh
    print_info "Recopilando información."

    while true; do
        read -rp "${PROMPT_PREFIX} Dominio: " DOMAIN_INPUT
        [[ "$DOMAIN_INPUT" =~ ^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]] && break || print_warning "Inválido."
    done

    while true; do
        read -rp "${PROMPT_PREFIX} Correo Certbot: " EMAIL_INPUT
        [[ "$EMAIL_INPUT" =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]] && break || print_warning "Inválido."
    done

    while true; do
        read -rp "${PROMPT_PREFIX} ¿IPv6? (y/n): " -n 1 r
        echo
        [[ "$r" =~ ^[Yy]$ ]] && { USE_IPV6_INPUT="yes"; break; }
        [[ "$r" =~ ^[Nn]$ ]] && { USE_IPV6_INPUT="no"; break; }
        print_warning "Inválido."
    done

    sed -i "s/^DOMAIN=.*/DOMAIN=\"$DOMAIN_INPUT\"/" config.sh
    sed -i "s/^EMAIL=.*/EMAIL=\"$EMAIL_INPUT\"/" config.sh
    sed -i "s/^USE_IPV6=.*/USE_IPV6=\"$USE_IPV6_INPUT\"/" config.sh

    print_success "Información guardada."
    echo ""
}

# Mensaje final tras completar la instalación
function print_final_message() {
    source common.sh
    source config.sh
    echo -e "${BLUE}──────────────────────────────────────────────────────────────────────────${NC}"
    echo -e "${GREEN}      ¡Felicitaciones! Tu servidor DNS over TLS está listo.      ${NC}"
    echo -e "${BLUE}──────────────────────────────────────────────────────────────────────────${NC}"
    print_warning "Se usó un certificado de PRUEBA (--staging). NO ES DE CONFIANZA."
    print_info "${BOLD}Para obtener el certificado REAL:${NC}"
    echo "  1. Edita: ${BOLD}sudo nano setup_certbot.sh${NC}"
    echo "  2. Comenta o elimina la línea: ${BOLD}local staging_flag=\"--staging\"${NC}"
    echo "  3. Ejecuta: ${BOLD}sudo ./setup_certbot.sh${NC}"
    echo "  4. Reinicia Nginx: ${BOLD}sudo systemctl restart nginx${NC}"
    echo -e "Tu DoT está en: ${YELLOW}${BOLD}$DOMAIN:$DOH_PORT${NC}"
    echo -e "${BLUE}──────────────────────────────────────────────────────────────────────────${NC}"
}

# ==================== EJECUCIÓN PRINCIPAL ====================
print_welcome_message
get_user_input
source common.sh
source config.sh
check_sudo_privileges

scripts=(
    "./detect_system.sh"
    "./check_dns.sh"
    "./setup_nginx.sh"
    "./setup_certbot.sh"
    "./setup_stubby.sh"
    "./setup_nginx_dot.sh"
    "./setup_firewall.sh"
    "./setup_extras.sh"
)

print_info "Iniciando secuencia de instalación..."
for script in "${scripts[@]}"; do
    if [ -f "$script" ]; then
        print_info "Ejecutando: ${BOLD}$script${NC}"
        if ! bash "$script"; then
            print_error_and_exit "La instalación falló en: $script"
        fi
        print_success "${BOLD}$script${NC} completado."
    else
        print_error_and_exit "Script no encontrado: $script"
    fi
done

INSTALL_COMPLETE=true
print_final_message
EOF


# ==============================================================================
#                                   FIN
# ==============================================================================
echo "----------------------------------------------------"
echo "✅  ¡Listo! Todos los scripts han sido creados."
echo "    Aplicando permisos de ejecución..."
chmod +x *.sh
echo "    Permisos aplicados."
echo ""
echo "👉  Ahora puedes ejecutar el script principal con:"
echo "    sudo ./main.sh"
echo ""
