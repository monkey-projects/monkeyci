FROM docker.io/nginx:1.27

EXPOSE 8080
EXPOSE 8081
EXPOSE 18080

ADD nginx.conf /etc/nginx/

ADD resources/public/ /var/www/html/
ADD target/js/frontend/ /var/www/html/
ADD target/index.html /var/www/html/
ADD target/admin/ /var/www/html/admin/
