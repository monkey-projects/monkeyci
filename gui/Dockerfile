FROM docker.io/nginx:1.29

EXPOSE 8080
EXPOSE 8081
EXPOSE 8082
EXPOSE 18080

ADD nginx.conf /etc/nginx/

ADD resources/public/ /var/www/html/
ADD target/js/frontend/js/*.js /var/www/html/js/
ADD target/index.html /var/www/html/
ADD target/admin/ /var/www/html/admin/
ADD target/error-404.html /var/www/html/

# Dashboard v2: experimental
ADD resources/dashboard/index.html /var/www/html/dashboard/
ADD target/js/dashboard/js/*.js /var/www/html/dashboard/js/
ADD target/js/dashboard/css/main.css /var/www/html/dashboard/css/
